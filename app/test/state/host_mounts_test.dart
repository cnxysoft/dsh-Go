import 'package:dsh_app/l10n/l10n.dart';
import 'package:dsh_app/runtime/runtime_state.dart';
import 'package:dsh_app/state/host_mounts.dart';
import 'package:dsh_app/ui/engine/host_mounts_page.dart';
import 'package:dsh_app/ui/workspace/directory_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../shell/golden_harness.dart' show FakeRuntime, goldenApp, runningStatus;

void main() {
  setUp(() {
    SharedPreferences.setMockInitialValues({});
    AppLocale.use(AppLocale.zhCN);
  });

  group('host_mounts model', () {
    test('normalizes paths and rejects invalid ones', () {
      expect(normalizeHostMountPath(' /sdcard//Projects/ '), '/sdcard/Projects');
      expect(normalizeHostMountPath('/data/local/tmp/work///'), '/data/local/tmp/work');
      expect(normalizeHostMountPath(''), isNull);
      expect(normalizeHostMountPath('/'), isNull);
      expect(normalizeHostMountPath('sdcard/Projects'), isNull);
      expect(normalizeHostMountPath('/sdcard/../etc'), isNull);
      expect(normalizeHostMountPath('/sdcard/a:b'), isNull);
    });

    test('parses, deduplicates paths and suffixes colliding names', () {
      const raw =
          '[{"name":"src","hostPath":"/sdcard/a/src/"},'
          '{"name":"src","hostPath":"/sdcard/b/src"},'
          '{"name":"dup","hostPath":"/sdcard/a/src"},'
          '{"name":"","hostPath":"/data/local/tmp/work"}]';
      final mounts = parseHostMountsJson(raw);
      expect(mounts, [
        const HostMount(name: 'src', hostPath: '/sdcard/a/src'),
        const HostMount(name: 'src-2', hostPath: '/sdcard/b/src'),
        const HostMount(name: 'work', hostPath: '/data/local/tmp/work'),
      ]);
      expect(mounts[1].guestPath, '/mnt/host/src-2');
      expect(parseHostMountsJson(encodeHostMountsJson(mounts)), mounts);
    });

    test('addHostMountEntry rejects duplicates and invalid inputs', () {
      final first = addHostMountEntry(const [], hostPath: '/sdcard/Projects', name: 'my code');
      expect(first.error, isNull);
      expect(first.added, const HostMount(name: 'my-code', hostPath: '/sdcard/Projects'));

      final dup = addHostMountEntry(first.mounts!, hostPath: '/sdcard/Projects/');
      expect(dup.error, HostMountError.duplicatePath);

      final bad = addHostMountEntry(first.mounts!, hostPath: 'relative');
      expect(bad.error, HostMountError.invalidPath);
    });
  });

  test('directoryShortcuts appends host mounts on local host', () {
    const mounts = [
      HostMount(name: 'projects', hostPath: '/sdcard/Projects'),
      HostMount(name: 'tmp', hostPath: '/data/local/tmp/work'),
    ];
    final local = directoryShortcuts(local: true, phoneStorage: false, hostMounts: mounts);
    expect(local.map((s) => s.path), containsAll(['/mnt/host/projects', '/mnt/host/tmp']));
    final projects = local.firstWhere((s) => s.path == '/mnt/host/projects');
    expect(projects.title, 'projects');
    expect(projects.subtitle, '/sdcard/Projects');

    final remote = directoryShortcuts(local: false, phoneStorage: false, hostMounts: mounts);
    expect(remote.map((s) => s.path), isNot(contains('/mnt/host/projects')));
  });

  testWidgets('HostMountsPage adds via picker or text, removes, and persists', (tester) async {
    await tester.pumpWidget(
      goldenApp(
        brightness: Brightness.light,
        overrides: [
          localRuntimeProvider.overrideWith(
            () => FakeRuntime(const LocalRuntimeState(loaded: true, status: runningStatus)),
          ),
        ],
        home: HostMountsPage(pickDirectory: () async => '/sdcard/MyRepo'),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('还没有宿主机挂载目录'), findsOneWidget);

    await tester.tap(find.text('添加宿主机目录'));
    await tester.pumpAndSettle();

    await tester.tap(find.text('从系统选择文件夹'));
    await tester.pumpAndSettle();
    expect(find.text('容器内路径：/mnt/host/MyRepo'), findsOneWidget);

    await tester.tap(find.widgetWithText(FilledButton, '添加'));
    await tester.pumpAndSettle();

    expect(find.text('MyRepo'), findsOneWidget);
    expect(find.text('/sdcard/MyRepo\n→ /mnt/host/MyRepo'), findsOneWidget);
    expect(find.text('重启引擎后生效'), findsOneWidget);

    final prefs = await SharedPreferences.getInstance();
    expect(prefs.getString('host_mounts'), contains('/sdcard/MyRepo'));

    await tester.tap(find.byTooltip('删除'));
    await tester.pumpAndSettle();
    expect(find.text('还没有宿主机挂载目录'), findsOneWidget);
  });
}
