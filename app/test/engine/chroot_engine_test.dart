import 'package:dsh_app/runtime/runtime_bridge.dart';
import 'package:dsh_app/runtime/runtime_state.dart';
import 'package:dsh_app/state/engine_control.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

class _FakeBridge extends RuntimeBridge {
  final calls = <String>[];

  @override
  Future<void> setChrootEngine(bool enabled) async {
    calls.add('setChroot:$enabled');
  }
}

class _FakeRuntime extends LocalRuntimeController {
  _FakeRuntime(this.initial);
  final RuntimeStatus initial;
  final calls = <String>[];

  @override
  LocalRuntimeState build() => LocalRuntimeState(loaded: true, status: initial);

  @override
  Future<void> stop() async {
    calls.add('stop');
    state = state.copyWith(status: const RuntimeStatus(phase: RuntimePhase.installed, chrootEngineAvailable: true));
  }

  @override
  Future<void> start() async {
    calls.add('start');
    state = state.copyWith(
      status: const RuntimeStatus(
        phase: RuntimePhase.running,
        chrootEngineEnabled: true,
        chrootEngineAvailable: true,
        chrootEngineActive: true,
        currentExecutionMode: 'chroot',
      ),
    );
  }
}

void main() {
  test('RuntimeStatus.fromMap parses chroot fields and exposes launchDiagnostics', () {
    final status = RuntimeStatus.fromMap(const {
      'phase': 'running',
      'chrootEngineEnabled': true,
      'chrootEngineAvailable': true,
      'chrootEngineActive': true,
      'currentExecutionMode': 'chroot',
      'launchSelectedMode': 'chroot',
    });
    expect(status.chrootEngineEnabled, isTrue);
    expect(status.chrootEngineAvailable, isTrue);
    expect(status.chrootEngineActive, isTrue);
    expect(status.currentExecutionMode, 'chroot');
    expect(status.launchDiagnostics['chrootEngineActive'], isTrue);
    expect(status.launchDiagnostics['currentExecutionMode'], 'chroot');
  });

  test('EngineControl.setChrootEngine stops, toggles, and restarts when running', () async {
    final bridge = _FakeBridge();
    final runtime = _FakeRuntime(const RuntimeStatus(phase: RuntimePhase.running, chrootEngineAvailable: true));
    final container = ProviderContainer(
      overrides: [
        runtimeBridgeProvider.overrideWithValue(bridge),
        localRuntimeProvider.overrideWith(() => runtime),
      ],
    );
    addTearDown(container.dispose);

    await EngineControl.setChrootEngine(container, enabled: true);
    expect(runtime.calls, ['stop', 'start']);
    expect(bridge.calls, ['setChroot:true']);
  });

  test('EngineControl.setChrootEngine toggles without starting when stopped', () async {
    final bridge = _FakeBridge();
    final runtime = _FakeRuntime(const RuntimeStatus(phase: RuntimePhase.installed, chrootEngineAvailable: true));
    final container = ProviderContainer(
      overrides: [
        runtimeBridgeProvider.overrideWithValue(bridge),
        localRuntimeProvider.overrideWith(() => runtime),
      ],
    );
    addTearDown(container.dispose);

    await EngineControl.setChrootEngine(container, enabled: true);
    expect(runtime.calls, isEmpty);
    expect(bridge.calls, ['setChroot:true']);
  });
}
