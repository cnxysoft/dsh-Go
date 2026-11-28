// Locked Android engine: verify WeakMap/WeakSet long-session history caching across
// pi-ai (adapter + sanitize-unicode + transform-messages + openai-responses-shared)
// and DeepSeek Messages (dsh-llm-deepseek) produces bit-for-bit identical payloads.
// Usage: DSH_PREFIX=<patched Android prefix> node --test runtime/tests/android-long-session-history-cache.test.mjs
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { join, resolve } from 'node:path';
import test from 'node:test';
import { pathToFileURL } from 'node:url';

const rawPrefix = process.env.DSH_TEST_PREFIX ?? process.env.DSH_PREFIX;
if (!rawPrefix) throw new Error('Set DSH_TEST_PREFIX or DSH_PREFIX to a patched Android runtime');
const prefix = resolve(rawPrefix);

const fileOf = rel => join(prefix, ...rel.split('/'));
const importOf = rel => import(pathToFileURL(fileOf(rel)));

const { sanitizeSurrogates } = await importOf('node_modules/@earendil-works/pi-ai/dist/utils/sanitize-unicode.js');
const { transformMessages } = await importOf('node_modules/@earendil-works/pi-ai/dist/api/transform-messages.js');
const { convertResponsesMessages } = await importOf('node_modules/@earendil-works/pi-ai/dist/api/openai-responses-shared.js');

function extractRegion(source, startMarker, endMarker = '//#endregion') {
  const start = source.indexOf(startMarker);
  assert.notEqual(start, -1, `missing region ${startMarker}`);
  const end = source.indexOf(endMarker, start);
  assert.notEqual(end, -1, `missing end of region ${startMarker}`);
  return source.slice(start, end);
}

test('all 5 patched JS modules pass node --check syntax validation', () => {
  for (const rel of [
    'node_modules/@deepseek-ai/dsh-llm-pi-ai/lib/index.js',
    'node_modules/@deepseek-ai/dsh-llm-deepseek/lib/index.js',
    'node_modules/@earendil-works/pi-ai/dist/utils/sanitize-unicode.js',
    'node_modules/@earendil-works/pi-ai/dist/api/transform-messages.js',
    'node_modules/@earendil-works/pi-ai/dist/api/openai-responses-shared.js',
  ]) {
    const res = spawnSync(process.execPath, ['--check', fileOf(rel)], { encoding: 'utf8' });
    assert.equal(res.status, 0, `${rel} syntax error: ${res.stderr}`);
  }
});

test('sanitizeSurrogates preserves valid text/emoji via isWellFormed fast path and strips lone surrogates', () => {
  assert.equal(sanitizeSurrogates('Hello 🙈 世界'), 'Hello 🙈 世界');
  assert.equal(sanitizeSurrogates(`a\uD83Db`), 'ab');
  assert.equal(sanitizeSurrogates(`a\uDC00b`), 'ab');
  assert.equal(sanitizeSurrogates(`ok \uD83D\uDE48 \uD83D end`), 'ok 🙈  end');
});

test('pi-ai transformMessages & convertResponsesMessages preserve frozen refs and avoid index-dependent mis-caching', () => {
  const modelA = { id: 'gpt-5.4', provider: 'openai', api: 'openai-responses', input: ['text', 'image'] };
  const modelB = { id: 'gpt-5.3', provider: 'openai', api: 'openai-responses', input: ['text', 'image'] };
  const allowed = new Set(['openai']);

  const signedAssistant = Object.freeze({
    role: 'assistant',
    provider: 'openai',
    api: 'openai-responses',
    model: 'gpt-5.4',
    stopReason: 'toolUse',
    content: Object.freeze([
      Object.freeze({
        type: 'thinking',
        thinking: '',
        thinkingSignature: JSON.stringify({ type: 'reasoning', id: 'rs_1', summary: [] }),
      }),
      Object.freeze({
        type: 'text',
        text: 'working',
        textSignature: JSON.stringify({ v: 1, id: 'msg_signed_1' }),
      }),
      Object.freeze({
        type: 'toolCall',
        id: 'call_1|fc_1',
        name: 'bash',
        arguments: Object.freeze({ cmd: 'ls' }),
      }),
    ]),
  });

  const unsignedAssistant = Object.freeze({
    role: 'assistant',
    provider: 'openai',
    api: 'openai-responses',
    model: 'gpt-5.4',
    stopReason: 'stop',
    content: Object.freeze([Object.freeze({ type: 'text', text: 'no signature' })]),
  });

  const toolResult = {
    role: 'toolResult',
    toolCallId: 'call_1|fc_1',
    toolName: 'bash',
    content: [{ type: 'text', text: 'ok \uD83D end' }],
    isError: false,
  };
  const whitespaceToolResult = {
    role: 'toolResult',
    toolCallId: 'call_2|fc_2',
    toolName: 'bash',
    content: [{ type: 'text', text: '   \n\t  ' }],
    isError: false,
  };

  // Same-model transform preserves frozen reference on warm turns
  const t1 = transformMessages([signedAssistant, toolResult], modelA);
  const t2 = transformMessages([signedAssistant, toolResult], modelA);
  assert.equal(t1[0], signedAssistant);
  assert.equal(t2[0], signedAssistant);

  // Cross-model transform drops thinking and never returns the cached same-model reference
  const tCross = transformMessages([signedAssistant], modelB);
  assert.notEqual(tCross[0], signedAssistant);
  assert.equal(tCross[0].content.some(b => b.type === 'thinking'), false);

  // Responses conversion: warm turn is bit-for-bit identical; lone surrogate stripped; whitespace tool result matches 1.5.0
  const r1 = convertResponsesMessages(modelA, { messages: [signedAssistant, toolResult, whitespaceToolResult] }, allowed);
  const r2 = convertResponsesMessages(modelA, { messages: [signedAssistant, toolResult, whitespaceToolResult] }, allowed);
  assert.equal(JSON.stringify(r1), JSON.stringify(r2));
  assert.equal(r1[3].output, 'ok  end');
  assert.equal(r1[4].output, '(no tool output)');

  // Unsigned assistant uses msgIndex fallback id and must NOT return stale msg_pi_0 after index shift
  const u1 = convertResponsesMessages(modelA, { messages: [unsignedAssistant] }, allowed);
  const u2 = convertResponsesMessages(modelA, { messages: [{ role: 'user', content: 'prefix' }, unsignedAssistant] }, allowed);
  assert.equal(u1[0].id, 'msg_pi_0');
  assert.equal(u2[1].id, 'msg_pi_1');
});

test('dsh-llm-pi-ai toPiAssistant caches frozen valid messages and never caches unfrozen or degraded ones', () => {
  const src = readFileSync(fileOf('node_modules/@deepseek-ai/dsh-llm-pi-ai/lib/index.js'), 'utf8');
  const region = extractRegion(src, '//#region lib/types/replay.js');
  class LlmError extends Error {
    constructor(msg, code) { super(msg); this.code = code; }
  }
  const { toPiAssistant } = new Function('LlmError', region + '\nreturn { toPiAssistant };')(LlmError);

  const frozenMsg = Object.freeze({
    role: 'assistant',
    content: Object.freeze([
      Object.freeze({ type: 'text', text: 'hello' }),
      Object.freeze({ type: 'tool-call', id: 'call_1|fc_1', name: 'read', arguments: '{"path":"a.txt"}' }),
    ]),
    source: Object.freeze({
      kind: 'model',
      provider: 'openai',
      model: 'gpt-5.4',
      replayState: Object.freeze({
        response: Object.freeze({
          kind: 'pi-ai',
          version: 2,
          api: 'openai-responses',
          provider: 'openai',
          model: 'gpt-5.4',
          stopReason: 'toolUse',
        }),
        blocks: Object.freeze([
          Object.freeze({ type: 'text', textSignature: '{"v":1,"id":"msg_1"}' }),
          Object.freeze({ type: 'tool-call' }),
        ]),
      }),
    }),
  });

  const a1 = toPiAssistant(frozenMsg);
  const a2 = toPiAssistant(frozenMsg);
  assert.equal(a1, a2);
  assert.equal(Object.isFrozen(a1), true);
  assert.equal(Object.isFrozen(a1.content), true);

  // Unfrozen message must not be cached
  const unfrozenMsg = {
    role: 'assistant',
    content: [{ type: 'text', text: 'hi' }],
    source: { kind: 'model', provider: 'openai', model: 'gpt-5.4' },
  };
  assert.notEqual(toPiAssistant(unfrozenMsg), toPiAssistant(unfrozenMsg));

  // Degraded replay must still invoke onDegrade every time
  const badReplay = Object.freeze({
    role: 'assistant',
    content: Object.freeze([Object.freeze({ type: 'text', text: 'x' })]),
    source: Object.freeze({ kind: 'model', provider: 'openai', model: 'gpt-5.4', replayState: Object.freeze({ bad: true }) }),
  });
  let degrades = 0;
  toPiAssistant(badReplay, () => degrades++);
  toPiAssistant(badReplay, () => degrades++);
  assert.equal(degrades, 2);
});

test('dsh-llm-deepseek serialize caches frozen assistant/user blocks safely across consecutive merges and model switches', () => {
  const src = readFileSync(fileOf('node_modules/@deepseek-ai/dsh-llm-deepseek/lib/index.js'), 'utf8');
  const replayRegion = extractRegion(src, '//#region lib/types/replay.js');
  const serializeRegion = extractRegion(src, '//#region lib/types/serialize.js');
  class LlmError extends Error {
    constructor(msg, code) { super(msg); this.code = code; }
  }
  const { serialize } = new Function(
    'LlmError', 'requestImageHandleText',
    replayRegion + '\n' + serializeRegion + '\nreturn { serialize };',
  )(LlmError, () => '');

  const connection = {
    models: [{ id: 'deepseek-chat' }, { id: 'deepseek-reasoner' }],
    defaults: { thinking: 'enabled', reasoningEffort: 'high' },
    maxTokens: 8192,
  };
  const user1 = Object.freeze({ role: 'user', content: Object.freeze([Object.freeze({ type: 'text', text: 'u1' })]) });
  const user2 = Object.freeze({ role: 'user', content: Object.freeze([Object.freeze({ type: 'text', text: 'u2' })]) });
  const asst = Object.freeze({
    role: 'assistant',
    content: Object.freeze([
      Object.freeze({ type: 'reasoning', text: 'think' }),
      Object.freeze({ type: 'text', text: 'answer' }),
      Object.freeze({ type: 'tool-call', id: 'tc_1', name: 'bash', arguments: '{"cmd":"pwd"}' }),
    ]),
    source: Object.freeze({
      kind: 'model',
      model: 'deepseek-reasoner',
      replayState: Object.freeze({
        response: Object.freeze({ kind: 'deepseek-messages', version: 1, model: 'deepseek-reasoner' }),
        blocks: Object.freeze([
          Object.freeze({ type: 'reasoning', signature: 'sig_abc' }),
          Object.freeze({ type: 'text' }),
          Object.freeze({ type: 'tool-call' }),
        ]),
      }),
    }),
  });
  const toolRes = Object.freeze({
    role: 'tool',
    toolCallId: 'tc_1',
    content: Object.freeze([Object.freeze({ type: 'text', text: '/tmp' })]),
  });

  // Consecutive user messages merge via previous.content.push(...content); cached arrays must never be mutated!
  const b1 = serialize({ model: 'deepseek-reasoner', system: 'sys' }, connection, [user1, user2, asst, toolRes], new Map(), () => '');
  const b2 = serialize({ model: 'deepseek-reasoner', system: 'sys' }, connection, [user1, user2, asst, toolRes], new Map(), () => '');
  assert.equal(JSON.stringify(b1), JSON.stringify(b2));
  assert.equal(b1.messages[0].content.length, 2);
  assert.equal(b2.messages[0].content.length, 2);
  assert.equal(b1.messages[1].content[0].signature, 'sig_abc');

  // Switching target model must NOT leak the deepseek-reasoner thinking signature into deepseek-chat
  const bCross = serialize({ model: 'deepseek-chat', system: 'sys' }, connection, [user1, asst, toolRes], new Map(), () => '');
  assert.equal('signature' in bCross.messages[1].content[0], false);

  // Degraded replay must still invoke onReplayDegrade on every call
  const badAsst = Object.freeze({
    role: 'assistant',
    content: Object.freeze([Object.freeze({ type: 'text', text: 'ok' })]),
    source: Object.freeze({ kind: 'model', model: 'deepseek-reasoner', replayState: Object.freeze({ broken: true }) }),
  });
  let degrades = 0;
  serialize({ model: 'deepseek-reasoner' }, connection, [user1, badAsst], new Map(), () => '', () => degrades++);
  serialize({ model: 'deepseek-reasoner' }, connection, [user1, badAsst], new Map(), () => '', () => degrades++);
  assert.equal(degrades, 2);
});
