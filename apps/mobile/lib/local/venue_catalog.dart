import 'local_api.dart';

/// Older cached catalogs and saved plans have no explicit campus field.
String venueCampus(Json venue) {
  final name = '${venue['stadium_name'] ?? ''}';
  final campus = '${venue['campus_name'] ?? ''}';
  for (final label in ['思明', '翔安']) {
    if (campus.contains(label) || name.contains(label)) return '$label校区';
  }
  final id = '${venue['stadium_id']}';
  if (const {'2', '4', '5', '6', '8', '9', '11', '17'}.contains(id)) {
    return '思明校区';
  }
  if (const {'12', '13', '14', '15', '16'}.contains(id)) return '翔安校区';
  return campus.trim().isNotEmpty ? campus.trim() : '其他校区（待确认）';
}

List<String> venueCampuses(List<Json> venues) =>
    venues.map(venueCampus).toSet().toList();
