import 'dart:async';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../l10n/l10n.dart';
import '../../runtime/runtime_bridge.dart';
import '../../runtime/runtime_state.dart';
import '../../state/engine_control.dart';
import '../../state/host_mounts.dart';
import '../../state/settings_state.dart';
import '../common/app_dialog.dart';
import '../common/list_page_row.dart';
import '../features/feature_widgets.dart';
import '../shell/page_frame.dart';
import '../theme/app_icons.dart';
import '../theme/theme.dart';

/// 设置 › 本机引擎 › 宿主机挂载目录: manage extra Android folders bind-mounted
/// under `/mnt/host/<name>` inside the local Linux environment.
class HostMountsPage extends ConsumerWidget {
  const HostMountsPage({super.key, this.pickDirectory});

  /// Directory picker override for tests; defaults to [FilePicker.getDirectoryPath].
  final Future<String?> Function()? pickDirectory;

  Future<String?> _pickFolder() => (pickDirectory ?? FilePicker.getDirectoryPath)();

  void _notifyRestartIfRunning(BuildContext context, WidgetRef ref) {
    if (ref.read(localRuntimeProvider).status.phase != RuntimePhase.running) return;
    final l10n = context.l10n;
    final container = ProviderScope.containerOf(context, listen: false);
    final messenger = ScaffoldMessenger.maybeOf(context);
    messenger?.hideCurrentSnackBar();
    messenger?.showSnackBar(
      SnackBar(
        content: Text(l10n.hubSettingsRestartRequired),
        action: SnackBarAction(
          label: l10n.hubSettingsRestartNow,
          onPressed: () => unawaited(EngineControl.restart(container)),
        ),
      ),
    );
  }

  Future<void> _showAddDialog(BuildContext context, WidgetRef ref) async {
    final added = await showAppDialog<bool>(
      context: context,
      builder: (_) => _AddHostMountDialog(
        existing: ref.read(settingsProvider).hostMounts,
        pickDirectory: _pickFolder,
        onAdd: (hostPath, name) => ref.read(settingsProvider.notifier).addHostMount(hostPath: hostPath, name: name),
      ),
    );
    if (added == true && context.mounted) {
      _notifyRestartIfRunning(context, ref);
    }
  }

  Future<void> _remove(BuildContext context, WidgetRef ref, HostMount mount) async {
    await ref.read(settingsProvider.notifier).removeHostMount(mount.hostPath);
    if (context.mounted) _notifyRestartIfRunning(context, ref);
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = context.l10n;
    final mounts = ref.watch(settingsProvider.select((s) => s.hostMounts));
    final phoneStorage = ref.watch(settingsProvider.select((s) => s.phoneStorage));
    final colors = DshColors.of(context);

    return AppPage.list(
      title: l10n.hubSettingsHostMounts,
      subtitle: Text(l10n.hubSettingsHostMountsSubtitle),
      actions: [
        IconButton(
          icon: const Icon(AppIcons.add),
          tooltip: l10n.hubSettingsHostMountsAdd,
          onPressed: () => unawaited(_showAddDialog(context, ref)),
        ),
      ],
      children: [
        if (mounts.isEmpty)
          FeatureMessage(
            icon: AppIcons.files,
            title: l10n.hubSettingsHostMountsEmpty,
            body: l10n.hubSettingsHostMountsEmptyHint,
            action: (l10n.hubSettingsHostMountsAdd, () => unawaited(_showAddDialog(context, ref))),
          )
        else ...[
          ListPageCard(
            title: l10n.hubSettingsHostMountsListTitle,
            children: [
              for (final mount in mounts)
                ListPageRow(
                  icon: AppIcons.files,
                  title: mount.name,
                  subtitle: '${mount.hostPath}\n→ ${mount.guestPath}',
                  subtitleMaxLines: 2,
                  trailing: IconButton(
                    icon: Icon(AppIcons.delete, color: colors.danger),
                    tooltip: l10n.commonDelete,
                    onPressed: () => unawaited(_remove(context, ref, mount)),
                  ),
                ),
            ],
          ),
          const SizedBox(height: 12),
          ListPageCard(
            children: [
              ListPageRow(
                icon: AppIcons.add,
                title: l10n.hubSettingsHostMountsAdd,
                onTap: () => unawaited(_showAddDialog(context, ref)),
              ),
            ],
          ),
        ],
        const SizedBox(height: 12),
        FeatureNote(l10n.hubSettingsHostMountsNote),
        if (!phoneStorage) ...[
          const SizedBox(height: 12),
          ListPageCard(
            children: [
              ListPageRow(
                icon: AppIcons.phoneStorage,
                title: l10n.hubSettingsHostMountsGrantStorage,
                subtitle: l10n.hubSettingsHostMountsGrantStorageHint,
                onTap: () => unawaited(ref.read(runtimeBridgeProvider).openAllFilesAccess()),
              ),
            ],
          ),
        ],
      ],
    );
  }
}

class _AddHostMountDialog extends StatefulWidget {
  const _AddHostMountDialog({required this.existing, required this.pickDirectory, required this.onAdd});

  final List<HostMount> existing;
  final Future<String?> Function() pickDirectory;
  final Future<HostMountError?> Function(String hostPath, String? name) onAdd;

  @override
  State<_AddHostMountDialog> createState() => _AddHostMountDialogState();
}

class _AddHostMountDialogState extends State<_AddHostMountDialog> {
  final _pathController = TextEditingController();
  final _nameController = TextEditingController();
  String? _error;
  bool _saving = false;
  bool _picking = false;

  @override
  void dispose() {
    _pathController.dispose();
    _nameController.dispose();
    super.dispose();
  }

  String? _previewGuestPath() {
    final path = normalizeHostMountPath(_pathController.text);
    if (path == null) return null;
    final baseName = normalizeHostMountName(_nameController.text, path);
    if (baseName == null) return null;
    final res = addHostMountEntry(widget.existing, hostPath: path, name: baseName);
    return res.added?.guestPath ?? '$hostMountGuestPrefix/$baseName';
  }

  Future<void> _pick() async {
    if (_picking || _saving) return;
    setState(() {
      _picking = true;
      _error = null;
    });
    try {
      final picked = await widget.pickDirectory();
      if (!mounted || picked == null || picked.trim().isEmpty) return;
      final normalized = normalizeHostMountPath(picked);
      if (normalized == null) {
        setState(() => _error = context.l10n.hubSettingsHostMountsInvalidPath);
        return;
      }
      _pathController.text = normalized;
      if (_nameController.text.trim().isEmpty) {
        _nameController.text = normalizeHostMountName(null, normalized) ?? '';
      }
      setState(() => _error = null);
    } catch (_) {
      if (mounted) setState(() => _error = context.l10n.hubSettingsHostMountsPickFailed);
    } finally {
      if (mounted) setState(() => _picking = false);
    }
  }

  Future<void> _submit() async {
    if (_saving) return;
    final l10n = context.l10n;
    setState(() {
      _saving = true;
      _error = null;
    });
    final error = await widget.onAdd(_pathController.text, _nameController.text);
    if (!mounted) return;
    if (error != null) {
      setState(() {
        _saving = false;
        _error = switch (error) {
          HostMountError.invalidPath => l10n.hubSettingsHostMountsInvalidPath,
          HostMountError.invalidName => l10n.hubSettingsHostMountsInvalidName,
          HostMountError.duplicatePath => l10n.hubSettingsHostMountsDuplicate,
        };
      });
      return;
    }
    Navigator.of(context).pop(true);
  }

  @override
  Widget build(BuildContext context) {
    final l10n = context.l10n;
    final preview = _previewGuestPath();
    final scheme = Theme.of(context).colorScheme;
    return AlertDialog(
      title: Text(l10n.hubSettingsHostMountsAdd),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            OutlinedButton.icon(
              onPressed: _picking || _saving ? null : () => unawaited(_pick()),
              icon: const Icon(AppIcons.files, size: 18),
              label: Text(l10n.hubSettingsHostMountsPickFolder),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _pathController,
              decoration: InputDecoration(
                labelText: l10n.hubSettingsHostMountsPathLabel,
                hintText: '/sdcard/Projects',
                errorText: _error,
              ),
              autocorrect: false,
              enableSuggestions: false,
              onChanged: (_) => setState(() => _error = null),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _nameController,
              decoration: InputDecoration(
                labelText: l10n.hubSettingsHostMountsNameLabel,
                hintText: l10n.hubSettingsHostMountsNameHint,
              ),
              autocorrect: false,
              enableSuggestions: false,
              onChanged: (_) => setState(() => _error = null),
            ),
            if (preview != null) ...[
              const SizedBox(height: 10),
              Text(
                l10n.hubSettingsHostMountsGuestPreview(preview),
                style: Theme.of(context).textTheme.bodySmall?.copyWith(color: scheme.onSurfaceVariant),
              ),
            ],
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: _saving ? null : () => Navigator.of(context).pop(false),
          child: Text(l10n.commonCancel),
        ),
        FilledButton(
          onPressed: _saving ? null : () => unawaited(_submit()),
          child: Text(l10n.commonAdd),
        ),
      ],
    );
  }
}
