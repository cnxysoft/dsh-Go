import 'dart:convert';

/// Guest mount-point prefix inside the local Linux environment (`HostMounts.GUEST_PREFIX`).
const hostMountGuestPrefix = '/mnt/host';
const _maxMountNameLength = 64;

/// A user-configured Android host folder mounted into the local Linux environment.
class HostMount {
  const HostMount({required this.name, required this.hostPath});

  final String name;
  final String hostPath;

  String get guestPath => '$hostMountGuestPrefix/$name';

  Map<String, String> toJson() => {'name': name, 'hostPath': hostPath};

  @override
  bool operator ==(Object other) => other is HostMount && other.name == name && other.hostPath == hostPath;

  @override
  int get hashCode => Object.hash(name, hostPath);
}

enum HostMountError { invalidPath, invalidName, duplicatePath }

/// Normalizes an absolute Android host path (mirrors `HostMounts.normalizeHostPath`).
String? normalizeHostMountPath(String raw) {
  final trimmed = raw.trim();
  if (!trimmed.startsWith('/')) return null;
  if (_hasUnsafeMountChars(trimmed)) return null;
  final parts = trimmed.split('/').where((p) => p.isNotEmpty).toList();
  if (parts.isEmpty || parts.any((p) => p == '.' || p == '..')) return null;
  return '/${parts.join('/')}';
}

/// Normalizes a single mount-point name, falling back to the last segment of [normalizedHostPath].
String? normalizeHostMountName(String? raw, String normalizedHostPath) {
  final trimmed = raw?.trim();
  final seed = (trimmed != null && trimmed.isNotEmpty)
      ? trimmed
      : normalizedHostPath.substring(normalizedHostPath.lastIndexOf('/') + 1);
  final buf = StringBuffer();
  for (final unit in seed.runes) {
    final ch = String.fromCharCode(unit);
    if (ch == '/' || ch == ':' || ch == '\n' || ch == '\r' || ch == '\u0000' || ch.trim().isEmpty) {
      buf.write('-');
    } else {
      buf.write(ch);
    }
  }
  var cleaned = buf.toString().replaceAll(RegExp(r'^-+|-+$'), '');
  if (cleaned.length > _maxMountNameLength) {
    cleaned = cleaned.substring(0, _maxMountNameLength);
  }
  if (cleaned.isEmpty || cleaned == '.' || cleaned == '..' || _hasUnsafeMountChars(cleaned) || cleaned.contains('/')) {
    return null;
  }
  return cleaned;
}

bool _hasUnsafeMountChars(String s) =>
    s.contains(':') || s.contains('\n') || s.contains('\r') || s.contains('\u0000');

String _deduplicateName(String base, Set<String> used) {
  if (!used.contains(base)) return base;
  var n = 2;
  while (true) {
    final suffix = '-$n';
    final maxBase = (_maxMountNameLength - suffix.length).clamp(1, _maxMountNameLength);
    final prefix = base.length > maxBase ? base.substring(0, maxBase) : base;
    final candidate = '$prefix$suffix';
    if (!used.contains(candidate)) return candidate;
    n++;
  }
}

/// Parses persisted JSON (`flutter.host_mounts`), dropping invalid/duplicate paths and deduplicating names.
List<HostMount> parseHostMountsJson(String? raw) {
  if (raw == null || raw.trim().isEmpty) return const [];
  final Object? decoded;
  try {
    decoded = jsonDecode(raw);
  } catch (_) {
    return const [];
  }
  if (decoded is! List) return const [];
  final seenPaths = <String>{};
  final usedNames = <String>{};
  final out = <HostMount>[];
  for (final item in decoded) {
    if (item is! Map) continue;
    final rawPath = item['hostPath'];
    if (rawPath is! String) continue;
    final hostPath = normalizeHostMountPath(rawPath);
    if (hostPath == null || !seenPaths.add(hostPath)) continue;
    final rawName = item['name'] is String ? item['name'] as String : null;
    final baseName = normalizeHostMountName(rawName, hostPath);
    if (baseName == null) continue;
    final uniqueName = _deduplicateName(baseName, usedNames);
    usedNames.add(uniqueName);
    out.add(HostMount(name: uniqueName, hostPath: hostPath));
  }
  return List.unmodifiable(out);
}

String encodeHostMountsJson(List<HostMount> mounts) => jsonEncode([for (final m in mounts) m.toJson()]);

/// Validates and appends a host mount entry, returning either the updated list or an error.
({List<HostMount>? mounts, HostMount? added, HostMountError? error}) addHostMountEntry(
  List<HostMount> existing, {
  required String hostPath,
  String? name,
}) {
  final normalizedPath = normalizeHostMountPath(hostPath);
  if (normalizedPath == null) return (mounts: null, added: null, error: HostMountError.invalidPath);
  if (existing.any((m) => m.hostPath == normalizedPath)) {
    return (mounts: null, added: null, error: HostMountError.duplicatePath);
  }
  final normalizedName = normalizeHostMountName(name, normalizedPath);
  if (normalizedName == null) return (mounts: null, added: null, error: HostMountError.invalidName);
  final usedNames = {for (final m in existing) m.name};
  final entry = HostMount(name: _deduplicateName(normalizedName, usedNames), hostPath: normalizedPath);
  return (mounts: List.unmodifiable([...existing, entry]), added: entry, error: null);
}
