// Android-only runtime patch. No real model, phone, data, or privileged execution.
// DSH_PREFIX=<patched Android prefix> node --test runtime/tests/android-phone-prompt.test.mjs
import assert from 'node:assert/strict';
import test from 'node:test';
import { join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const load = name => import(pathToFileURL(join(process.env.DSH_PREFIX, 'node_modules/@deepseek-ai', name, 'lib/index.js')));
const id = 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee';
const user = rpcId => ({ source: { kind: 'user', rpcId } });

async function agent(t) {
  const { Context } = await load('cordis');
  const ctx = new Context();
  t.after(() => ctx.fiber.dispose());
  for (const name of ['dsh-session', 'dsh-session-projection', 'dsh-agent', 'dsh-llm', 'dsh-tools', 'dsh-system-prompt']) {
    await ctx.plugin((await load(name)).default);
  }
  await ctx.plugin((await load('dsh-agent-loop')).AgentLoop, { agents: [] });
  return { ctx, a: (await ctx.agents.create({ sessionId: 'prompt-source-fixture' })).agent };
}

// Exercise the real turn driver; replace model/step execution with an observer.
async function turn(a, inputs) {
  const seen = [];
  a.phase = { kind: 'running', turn: a.phase.turn ?? 0, step: 0, abort: new AbortController() };
  a.preStep = async () => ({ messages: inputs[seen.length], toolSet: { dispose() {} } });
  a.step = async (_, promptId) => {
    seen.push(promptId);
    return seen.length === inputs.length ? { kind: 'completed' } : null;
  };
  await a.turn();
  return seen;
}

test('sole prompt correlates through tool-only steps, not through steering or the next turn', async t => {
  const { a } = await agent(t);
  assert.deepEqual(await turn(a, [[user(id)], [], [user('unrelated')], []]), [id, id, undefined, undefined]);
  assert.deepEqual(await turn(a, [[user(id), user(id)], []]), [undefined, undefined]);
  assert.deepEqual(await turn(a, [[{ source: { kind: 'runtime-context', rpcId: id } }]]), [undefined]);
  assert.deepEqual(await turn(a, [[user(id + '\n')]]), [undefined]);
  assert.deepEqual(await turn(a, [[user(id)]]), [id]);
});

test('MCP carries the batch correlation, never the ID placed in tool arguments', async t => {
  const { ctx, a } = await agent(t);
  await ctx.plugin((await load('dsh-mcp-resources')).default);
  await ctx.plugin(await load('dsh-mcp-client'), {
    serverName: 'phone', transport: 'stdio', command: process.execPath,
    args: [fileURLToPath(new URL('./fixtures/phone-caller-mcp.mjs', import.meta.url)), 'supported'],
    dshGoPhoneCaller: true, failOnStartupError: true, reconnect: { enabled: false },
  });
  const { NativeToolExecutor } = await load('dsh-tool-executor-native');
  const executor = new NativeToolExecutor(ctx);
  for (const [index, hint] of [id, undefined].entries()) {
    const result = await executor.execute({
      agent: a, log: { turn: 1, step: index + 1 }, signal: new AbortController().signal,
      ...(hint ? { phonePromptId: hint } : {}),
      calls: [{ id: `synthetic-${index}`, name: 'mcp__phone__screen', arguments: JSON.stringify({ promptRequestId: 'from-tool' }) }],
    });
    const reply = result.results[0].result;
    assert.equal(reply.isError, false);
    const { caller, args } = JSON.parse(reply.value.content[0].text);
    assert.equal(args.promptRequestId, 'from-tool');
    assert.equal(caller.promptRequestId, hint);
  }
});
