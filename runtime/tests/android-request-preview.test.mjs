// Locked Android engine: exercise both provider budgets and the actual attachment encoder.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import test from 'node:test';
import { pathToFileURL } from 'node:url';

const prefix = resolve(process.env.DSH_PREFIX);
const load = name => import(pathToFileURL(join(prefix, 'node_modules/@deepseek-ai', name, 'lib/index.js')));
const { apply } = await load('dsh-llm-pi-ai');
const { resolveRequestImageTarget } = await load('dsh-llm-deepseek');
const { readRequestImageFile } = await load('dsh-attachment-local');

function piTarget(maxPixels, maxBytes) {
  let adapter;
  const profile = { baseURL: 'https://fixture.invalid', api: 'openai-responses', models: [{ id: 'vision' }],
    requestImagePixelBudget: maxPixels, requestImageMaxBytes: maxBytes };
  apply({ fiber: {}, on() {}, inject() {}, llm: { registerConfigurableProviders() {}, registerModelDiscovery() {},
    registerAdapter(_routes, value) { adapter = value; } } }, { providers: { get: () => ({ fixture: profile }) } });
  return image => adapter.imageRequestTarget('fixture', 'vision', image);
}

for (const [name, target] of [
  ['Pi', piTarget],
  ['DeepSeek', (pixels, bytes) => image => resolveRequestImageTarget({ imagePixelBudget: pixels, imageMaxBytes: bytes }, image)],
]) {
  test(`${name}: cap preview size without enlarging or overriding tighter route limits`, () => {
    const normal = target(4194304, 2097152);
    assert.deepEqual(normal({ width: 1260, height: 2800 }), { width: 720, height: 1600, maxBytes: 1048576 });
    assert.deepEqual(normal({ width: 3200, height: 1440 }), { width: 1600, height: 720, maxBytes: 1048576 });
    assert.deepEqual(normal({ width: 30, height: 20 }), { width: 30, height: 20, maxBytes: 1048576 });
    assert.deepEqual(target(360000, 250000)({ width: 2000, height: 2000 }), { width: 600, height: 600, maxBytes: 250000 });
  });
}

test('a small opaque source also gets a P1 preview, recoverable under the same cache key', async t => {
  const cache = await mkdtemp(join(tmpdir(), 'dsh-request-preview-'));
  t.after(() => rm(cache, { recursive: true, force: true }));
  const data = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAIAAAABCAIAAAB7QOjdAAAAD0lEQVR4nGP4z8DA8J8BAAf/Af8Bf4mnAAAAAElFTkSuQmCC', 'base64');
  const ref = { attachmentId: 'sha256:' + createHash('sha256').update(data).digest('hex'),
    bytes: data.length, width: 2, height: 1, mediaType: 'image/png' };
  const target = piTarget(4194304, 2097152)(ref);
  const read = () => readRequestImageFile(cache, { ref, data }, target);
  const first = await read();
  // DC P1 descriptor for this synthetic PNG and request target, not a hash of private media.
  assert.equal(first.variantId, 'sha256:bdce055e954b18e72d03eb9afd5fc344eda33a07b7d98313141e17d0cb793932');
  assert.equal(first.mediaType, 'image/jpeg');
  assert.deepEqual([first.width, first.height], [2, 1]);
  assert.deepEqual((await read()).data, first.data);
});
