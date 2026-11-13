import 'package:flutter/material.dart';
import 'local_api.dart';

num? _number(Object? value) => value is num ? value : num.tryParse('$value');

/// Mirrors the school's availability flags; capacity alone does not imply bookability.
class SlotAvailability {
  const SlotAvailability(this.booked, this.total, this.reason);
  final int booked, total;
  final String? reason;
  bool get available => reason == null;

  factory SlotAvailability.from(Json row) {
    final raw = row['slots'];
    final entries = raw is List ? raw.whereType<Map>().toList() : [row];
    final defaultTotal = _number(row['personal_num']) ?? 0;
    var booked = 0, total = 0;
    var open = false, hasAvailableStatus = false;
    for (final slot in entries) {
      final used = _number(slot['selected'] ?? slot['booked']) ?? 0;
      final capacity =
          _number(slot['personal_num'] ?? slot['capacity'] ?? slot['total']) ??
              defaultTotal;
      booked += used.toInt();
      total += capacity.toInt();
      final statusAvailable = slot['status'] == 'available' &&
          (slot['date'] == null || slot['date'] == row['date']) &&
          '${slot['is_can_choose']}' != '0';
      hasAvailableStatus |= statusAvailable;
      open |= statusAvailable && capacity > used;
    }
    String? reason;
    if ('${row['is_can_choose']}' == '0' ||
        (row['status'] != null && row['status'] != 'available')) {
      reason = '${row['is_can_choose_tips'] ?? ''}'.trim();
      if (reason.isEmpty) reason = '未开放预约';
    } else if ((_number(row['venue_appointment_min_num']) ?? 0) > 1) {
      reason = '暂不支持多人预约';
    } else if (!open) {
      reason = !hasAvailableStatus ? '未开放预约' : '已约满';
    }
    return SlotAvailability(booked, total, reason);
  }
}

class SlotOption extends StatelessWidget {
  const SlotOption(
      {super.key,
      required this.range,
      required this.availability,
      required this.priority,
      required this.busy,
      required this.onToggle,
      this.allowPlanning = false,
      this.onEarlier,
      this.onLater});
  final String range;
  final SlotAvailability availability;
  final int? priority;
  final bool busy;

  /// Future plan candidates need not be bookable at edit time. This never changes
  /// the school's availability flags or permits an immediate booking.
  final bool allowPlanning;
  final VoidCallback onToggle;
  final VoidCallback? onEarlier, onLater;

  @override
  Widget build(BuildContext context) {
    final selected = priority != null;
    final available = availability.available;
    final plannable = allowPlanning && !available;
    final selectable = available || allowPlanning;
    final foreground = selected
        ? Colors.white
        : selectable
            ? const Color(0xFF142F48)
            : Colors.grey.shade700;
    final label = available
        ? '$range (已预约/总量: ${availability.booked}/${availability.total})'
        : plannable
            ? '$range（可预设，当前${availability.reason}）'
            : '$range（不可预约：${availability.reason}）';
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Material(
        key: ValueKey('slot-row-$range'),
        color: Colors.white,
        shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(8),
            side: const BorderSide(color: Color(0xFF7A8E9E))),
        clipBehavior: Clip.antiAlias,
        child: IntrinsicHeight(
            child: Row(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Expanded(
                child: Semantics(
              selected: selected,
              button: true,
              enabled: !busy && (selectable || selected),
              child: Material(
                key: ValueKey('slot-button-$range'),
                color: selected
                    ? Colors.green.shade600
                    : selectable
                        ? Colors.white
                        : Colors.grey.shade200,
                child: InkWell(
                  onTap: busy || (!selectable && !selected) ? null : onToggle,
                  child: ConstrainedBox(
                    constraints: const BoxConstraints(minHeight: 48),
                    child: Padding(
                      padding: const EdgeInsets.all(8),
                      child: Align(
                          alignment: Alignment.centerLeft,
                          child: Text(label,
                              style: TextStyle(
                                  color: foreground,
                                  fontSize: 11,
                                  fontWeight: FontWeight.w500))),
                    ),
                  ),
                ),
              ),
            )),
            SizedBox(
                width: 96,
                child: Visibility(
                  visible: selected,
                  maintainSize: true,
                  maintainAnimation: true,
                  maintainState: true,
                  child: Row(children: [
                    SizedBox(
                        width: 24,
                        child: IconButton(
                            icon: const Icon(Icons.remove, size: 16),
                            padding: EdgeInsets.zero,
                            constraints: const BoxConstraints(
                                minWidth: 24, minHeight: 48),
                            style: IconButton.styleFrom(
                                minimumSize: const Size(24, 48),
                                maximumSize: const Size(24, 48),
                                tapTargetSize:
                                    MaterialTapTargetSize.shrinkWrap),
                            tooltip: '优先级调高（提前）',
                            onPressed: busy ? null : onEarlier)),
                    Expanded(
                        child: Text('优先级 ${priority ?? '—'}',
                            textAlign: TextAlign.center,
                            style: const TextStyle(fontSize: 10.5))),
                    SizedBox(
                        width: 24,
                        child: IconButton(
                            icon: const Icon(Icons.add, size: 16),
                            padding: EdgeInsets.zero,
                            constraints: const BoxConstraints(
                                minWidth: 24, minHeight: 48),
                            style: IconButton.styleFrom(
                                minimumSize: const Size(24, 48),
                                maximumSize: const Size(24, 48),
                                tapTargetSize:
                                    MaterialTapTargetSize.shrinkWrap),
                            tooltip: '优先级调低（延后）',
                            onPressed: busy ? null : onLater)),
                  ]),
                )),
          ],
        )),
      ),
    );
  }
}
