import 'package:flutter/material.dart';
import 'local_api.dart';
import 'venue_catalog.dart';
import 'slot_option.dart';

class LocalPlanEditor extends StatefulWidget {
  const LocalPlanEditor({
    super.key,
    required this.venues,
    this.plan,
    this.initialVenue,
  });
  final List<Json> venues;
  final Json? plan;
  final Json? initialVenue;
  @override
  State<LocalPlanEditor> createState() => _LocalPlanEditorState();
}

class _LocalPlanEditorState extends State<LocalPlanEditor> {
  final form = GlobalKey<FormState>();
  final name = TextEditingController();
  final release = TextEditingController(text: '07:00:00');
  final execute = TextEditingController(text: '07:00:00');
  final daysBefore = TextEditingController(text: '1');
  final window = TextEditingController(text: '120');
  final lead = TextEditingController(text: '15');
  final priority = TextEditingController(text: '1');
  final ranges = List.generate(7, (_) => TextEditingController());
  static const weekdays = ['一', '二', '三', '四', '五', '六', '日'];
  late Json venue;
  late List<Json> venues;
  String get campus => venueCampus(venue);
  List<Json> get campusVenues =>
      venues.where((v) => venueCampus(v) == campus).toList();
  late DateTime date;
  String repeat = 'ONCE';
  String executionMode = 'TIMED';
  int pollSeconds = 30;
  late int monitorStartAt, monitorEndAt;
  bool confirmed = false, busy = false;
  String? error;
  String? slotMessage;
  List<Json> slots = [];

  static String venueKey(Json v) =>
      '${v['stadium_id']}_${v['venue_id']}_${v['category_id']}';

  static String venueLabel(Json v) {
    final sName = '${v['stadium_name']}'.trim();
    final pName = '${v['project_name'] ?? ''}'.trim();
    if (pName.isNotEmpty && !sName.contains(pName)) {
      return '$sName（$pName）';
    }
    return sName;
  }

  @override
  void initState() {
    super.initState();
    final p = widget.plan;
    executionMode = '${p?['execution_mode'] ?? 'TIMED'}';
    pollSeconds = (p?['poll_seconds'] as num?)?.toInt() ?? 30;
    monitorStartAt = (p?['monitor_start_at'] as num?)?.toInt() ?? DateTime.now().millisecondsSinceEpoch;
    monitorEndAt = (p?['monitor_end_at'] as num?)?.toInt() ?? monitorStartAt + 3600000;
    venue = p?['venue'] as Json? ?? widget.initialVenue ?? widget.venues.first;
    venues = [...widget.venues];
    final current = venues.where((v) => venueKey(v) == venueKey(venue));
    if (current.isEmpty) {
      venues.add(venue);
    } else {
      venue = current.first;
    }
    date = DateTime.tryParse(p?['date']?.toString() ?? '') ??
        beijingToday().add(const Duration(days: 1));
    if (p != null) {
      name.text = '${p['name']}';
      repeat = '${p['repeat']}';
      release.text = '${p['release_time']}';
      execute.text = '${p['execution_time']}';
      daysBefore.text = '${p['days_before']}';
      window.text = '${p['window_seconds']}';
      lead.text = '${p['prepare_lead_seconds']}';
      priority.text = '${p['priority']}';
      confirmed = p['rule_confirmed'] == true;
      final days = p['weekdays'] as Map;
      for (var i = 0; i < 7; i++) {
        ranges[i].text = ((days['${i + 1}'] as List?) ?? []).join(', ');
      }
    }
    for (final c in ranges) {
      c.addListener(() {
        if (mounted) setState(() {});
      });
    }
  }

  @override
  void dispose() {
    for (final c in [
      name,
      release,
      execute,
      daysBefore,
      window,
      lead,
      priority,
      ...ranges
    ]) {
      c.dispose();
    }
    super.dispose();
  }

  List<String> getSelectedRanges(int weekdayIndex) {
    return ranges[weekdayIndex]
        .text
        .split(RegExp(r'[,，]'))
        .map((e) => e.trim())
        .where((e) => e.isNotEmpty)
        .toList();
  }

  void toggleSlot(String timeRange) {
    final weekdayIndex = date.weekday - 1;
    final current = getSelectedRanges(weekdayIndex);
    if (current.contains(timeRange)) {
      current.remove(timeRange);
    } else {
      current.add(timeRange);
    }
    ranges[weekdayIndex].text = current.join(', ');
    setState(() {});
  }

  void swapPriority(int weekdayIndex, int fromIndex, int toIndex) {
    final current = getSelectedRanges(weekdayIndex);
    if (fromIndex < 0 ||
        fromIndex >= current.length ||
        toIndex < 0 ||
        toIndex >= current.length) {
      return;
    }
    final item = current.removeAt(fromIndex);
    current.insert(toIndex, item);
    ranges[weekdayIndex].text = current.join(', ');
    setState(() {});
  }

  String get day => date.toIso8601String().substring(0, 10);
  bool get isFutureDate => date.isAfter(beijingToday());
  bool get monitoring => executionMode == 'MONITOR';

  Future<void> pickMonitorTime(bool start) async {
    final millis = start ? monitorStartAt : monitorEndAt;
    final wall = DateTime.fromMillisecondsSinceEpoch(millis, isUtc: true).add(const Duration(hours: 8));
    final today = beijingToday();
    final last = date.isBefore(today) ? today : date;
    var initial = DateTime(wall.year, wall.month, wall.day);
    if (initial.isBefore(today)) initial = today;
    if (initial.isAfter(last)) initial = last;
    final day = await showDatePicker(context: context, firstDate: today, lastDate: last, initialDate: initial);
    if (day == null || !mounted) return;
    final time = await showTimePicker(context: context, initialTime: TimeOfDay(hour: wall.hour, minute: wall.minute));
    if (time == null || !mounted) return;
    final value = DateTime.utc(day.year, day.month, day.day, time.hour, time.minute)
        .subtract(const Duration(hours: 8)).millisecondsSinceEpoch;
    setState(() { if (start) { monitorStartAt = value; } else { monitorEndAt = value; } });
  }
  bool timeValid(String? value) =>
      RegExp(r'^(?:[01]\d|2[0-3]):[0-5]\d:[0-5]\d$').hasMatch(value ?? '');
  Widget text(String label, TextEditingController controller,
          {String? hint, String? Function(String?)? validate}) =>
      Padding(
          padding: const EdgeInsets.symmetric(vertical: 8),
          child: TextFormField(
              controller: controller,
              enabled: !busy,
              decoration: InputDecoration(labelText: label, helperText: hint),
              validator: validate ??
                  (s) => (s ?? '').trim().isEmpty ? '请填写此项' : null));
  Future<void> query() async {
    setState(() {
      busy = true;
      error = null;
      slotMessage = null;
    });
    try {
      final data = await const LocalApi().availability({...venue, 'date': day});
      if (mounted) {
        final totalCapacity = data['personal_num'];
        final canChoose = data['is_can_choose'];
        final tips = data['is_can_choose_tips']?.toString();
        final rawList = (data['time_slot_list'] as List?)?.cast<Json>() ?? [];
        final daySlots = rawList.where((r) => r['date'] == day).toList();
        final matchedSlots = daySlots
            .map((r) => {
                  ...r,
                  if ('$canChoose' == '0') 'is_can_choose': 0,
                  if (tips != null) 'is_can_choose_tips': tips,
                  'venue_appointment_min_num':
                      data['venue_appointment_min_num'],
                  if (totalCapacity != null && !r.containsKey('personal_num'))
                    'personal_num': totalCapacity,
                })
            .toList();
        setState(() {
          final resolved = data['resolved_target'];
          if (resolved is Map<String, dynamic>) {
            final oldKey = venueKey(venue);
            final updated = {...venue, ...resolved};
            if (oldKey != venueKey(updated)) confirmed = false;
            venues.removeWhere((v) =>
                venueKey(v) == oldKey || venueKey(v) == venueKey(updated));
            venue = updated;
            venues.add(venue);
          }
          slots = matchedSlots;
          if (canChoose == 0 || (tips != null && tips.isNotEmpty)) {
            slotMessage =
                (tips != null && tips.isNotEmpty) ? tips : '该场馆暂未开放预约或暂无时段数据';
          } else if (slots.isEmpty) {
            slotMessage = '当前日期暂未查询到可预约时段';
          }
        });
      }
    } catch (e) {
      if (mounted) setState(() => error = localError(e));
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  void changeVenue(Json selected) {
    venue = selected;
    slots = [];
    slotMessage = null;
    error = null;
    confirmed = false;
  }

  Future<void> save() async {
    if (!form.currentState!.validate()) return;
    final weekly = <String, List<String>>{};
    for (var i = 0; i < 7; i++) {
      if (repeat == 'ONCE' && i != date.weekday - 1) continue;
      final entries = ranges[i]
          .text
          .split(RegExp(r'[,，]'))
          .map((s) => s.trim())
          .where((s) => s.isNotEmpty)
          .toList();
      if (entries.isNotEmpty) weekly['${i + 1}'] = entries;
    }
    if (weekly.isEmpty) {
      setState(() => error = '请至少填写一个入场日的候选时段');
      return;
    }
    if (monitoring && (monitorEndAt <= monitorStartAt || monitorEndAt <= DateTime.now().millisecondsSinceEpoch ||
        monitorEndAt - monitorStartAt > 72 * 3600000 || monitorEndAt >
          DateTime.utc(date.year, date.month, date.day + 1).subtract(const Duration(hours: 8)).millisecondsSinceEpoch)) {
      setState(() => error = '监控截止须晚于开始和当前时间，最长72小时，且不超过入场日期');
      return;
    }
    if (!confirmed) {
      setState(() => error = monitoring ? '请确认发现空位后自动预约' : '启用前请确认学校放号规则');
      return;
    }
    setState(() {
      busy = true;
      error = null;
    });
    try {
      final cleanVenue = {
        for (final key in [
          'stadium_id',
          'venue_id',
          'category_id',
          'stadium_name',
          'project_name'
        ])
          key: venue[key]
      };
      await const LocalApi().savePlan({
        if (widget.plan?['id'] != null) 'id': widget.plan!['id'],
        'name': name.text.trim(),
        'venue': cleanVenue,
        'repeat': repeat,
        'execution_mode': executionMode,
        if (monitoring) ...{
          'monitor_start_at': monitorStartAt, 'monitor_end_at': monitorEndAt, 'poll_seconds': pollSeconds,
        },
        'date': day,
        'weekdays': weekly,
        'release_time': monitoring ? '00:00:00' : release.text,
        'execution_time': monitoring ? '00:00:00' : execute.text,
        'days_before': monitoring ? 0 : int.parse(daysBefore.text),
        'window_seconds': monitoring ? 120 : int.parse(window.text),
        'prepare_lead_seconds': monitoring ? 0 : int.parse(lead.text),
        'priority': int.parse(priority.text),
        'rule_confirmed': confirmed,
        'enabled': true,
        if (widget.plan?['id'] != null && widget.plan?['last_result'] is Map)
          'reset_execution': true,
      });
      if (mounted) Navigator.pop(context, true);
    } catch (e) {
      if (mounted) setState(() => error = localError(e));
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
      appBar: AppBar(title: Text(widget.plan == null ? '新建本机计划' : '编辑本机计划')),
      body: Form(
          key: form,
          child: ListView(padding: const EdgeInsets.all(20), children: [
            const Text('计划保存在这台手机，保存后自动启用，可在计划菜单暂停。'),
            text('计划名称', name),
            Padding(padding: const EdgeInsets.symmetric(vertical: 8), child: DropdownButtonFormField<String>(
              key: const ValueKey('execution-mode'), initialValue: executionMode,
              decoration: const InputDecoration(labelText: '执行方式'), items: const [
                DropdownMenuItem(value: 'TIMED', child: Text('定时执行')),
                DropdownMenuItem(value: 'MONITOR', child: Text('监控余量')),
              ], onChanged: busy ? null : (value) => setState(() {
                executionMode = value!; if (monitoring) repeat = 'ONCE'; confirmed = false;
              }))),
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 8),
              child: DropdownButtonFormField<String>(
                key: const ValueKey('plan-campus'),
                initialValue: campus,
                isExpanded: true,
                decoration: const InputDecoration(labelText: '校区'),
                items: venueCampuses(venues)
                    .map((c) => DropdownMenuItem(value: c, child: Text(c)))
                    .toList(),
                onChanged: busy
                    ? null
                    : (c) => setState(() => changeVenue(
                        venues.firstWhere((v) => venueCampus(v) == c))),
              ),
            ),
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 8),
              child: DropdownButtonFormField<String>(
                  key: ValueKey('plan-venue-$campus-${venueKey(venue)}'),
                  initialValue: venueKey(venue),
                  isExpanded: true,
                  decoration: const InputDecoration(labelText: '场馆'),
                  items: campusVenues
                      .map((v) => DropdownMenuItem<String>(
                          value: venueKey(v),
                          child: Text(
                            venueLabel(v),
                            overflow: TextOverflow.ellipsis,
                          )))
                      .toList(),
                  onChanged: busy
                      ? null
                      : (v) => setState(() {
                            changeVenue(campusVenues
                                .firstWhere((item) => venueKey(item) == v));
                          })),
            ),
            if (!monitoring) Padding(
              padding: const EdgeInsets.symmetric(vertical: 8),
              child: DropdownButtonFormField<String>(
                  initialValue: repeat,
                  decoration: const InputDecoration(labelText: '计划类型'),
                  items: const [
                    DropdownMenuItem(value: 'ONCE', child: Text('单次')),
                    DropdownMenuItem(value: 'WEEKLY', child: Text('每周'))
                  ],
                  onChanged: busy ? null : (v) => setState(() => repeat = v!)),
            ),
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 8),
              child: Material(
                  key: const ValueKey('entry-date-surface'),
                  color: Colors.white,
                  clipBehavior: Clip.antiAlias,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(12),
                    side: const BorderSide(color: Color(0xFF7A8E9E)),
                  ),
                  child: ListTile(
                      contentPadding: const EdgeInsets.symmetric(
                          horizontal: 14, vertical: 4),
                      title: Text('入场日期：$day（星期${weekdays[date.weekday - 1]}）'),
                      trailing: const Icon(Icons.calendar_month),
                      onTap: busy
                          ? null
                          : () async {
                              final today = beijingToday();
                              final picked = await showDatePicker(
                                  context: context,
                                  initialDate:
                                      date.isBefore(today) ? today : date,
                                  firstDate: today,
                                  lastDate:
                                      today.add(const Duration(days: 365)));
                              if (picked != null && mounted) {
                                setState(() {
                                  date = picked;
                                  slots = [];
                                  slotMessage = null;
                                });
                              }
                            })),
            ),
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 8),
              child: OutlinedButton(
                  onPressed: busy ? null : query,
                  child: const Text('查询学校时段与余量')),
            ),
            if (slotMessage != null)
              Container(
                margin: const EdgeInsets.symmetric(vertical: 6),
                padding:
                    const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                decoration: BoxDecoration(
                  color: Colors.amber.shade50,
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(color: Colors.amber.shade300),
                ),
                child: Row(
                  children: [
                    Icon(Icons.info_outline,
                        size: 18, color: Colors.amber.shade900),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        slotMessage!,
                        style: TextStyle(
                          fontSize: 12,
                          color: Colors.amber.shade900,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            if (slots.isNotEmpty) ...[
              Padding(
                padding: const EdgeInsets.only(top: 8, bottom: 4),
                child: Text(
                  monitoring ? '选择要监控的时段，可选择已约满或尚未开放的时段。发现空位后会按候选优先级尝试预约。' : isFutureDate
                      ? '未来时段可提前选择为计划候选；未开放或已约满不影响预设。到执行时间会重新检查学校开放状态与余量，不保证预约成功。'
                      : '学校返回时段（点击左侧时段选择/取消，右侧按键增减优先级）：',
                  style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600),
                ),
              ),
              Column(
                children: slots.map((s) {
                  final r = '${s['time_range']}'.trim();
                  final weekdayIndex = date.weekday - 1;
                  final selectedList = getSelectedRanges(weekdayIndex);
                  final index = selectedList.indexOf(r);
                  final priorityNum = index >= 0 ? index + 1 : null;
                  return SlotOption(
                    range: r,
                    availability: SlotAvailability.from(s),
                    allowPlanning: isFutureDate || monitoring,
                    priority: priorityNum,
                    busy: busy,
                    onToggle: () => toggleSlot(r),
                    onEarlier: index > 0
                        ? () => swapPriority(weekdayIndex, index, index - 1)
                        : null,
                    onLater: index >= 0 && index < selectedList.length - 1
                        ? () => swapPriority(weekdayIndex, index, index + 1)
                        : null,
                  );
                }).toList(),
              ),
              const SizedBox(height: 12),
            ],
            for (var i = 0; i < 7; i++)
              if (repeat == 'WEEKLY' || i == date.weekday - 1)
                text('星期${weekdays[i]}候选时段', ranges[i],
                    hint: '例如 08:00-09:00, 09:00-10:00，顺序即优先级', validate: (s) {
                  if ((s ?? '').trim().isEmpty) return null;
                  final r =
                      s!.split(RegExp(r'[,，]')).map((e) => e.trim()).toList();
                  if (r.toSet().length != r.length) {
                    return '候选时段不能重复';
                  }
                  for (final t in r) {
                    if (!RegExp(r'^(?:[01]\d|2[0-3]):[0-5]\d-(?:[01]\d|2[0-3]):[0-5]\d$')
                            .hasMatch(t) ||
                        t.split('-')[0].compareTo(t.split('-')[1]) >= 0) {
                      return '请填写有效的起止时间';
                    }
                  }
                  return null;
                }),
            const SizedBox(height: 16),
            if (!monitoring) ...[
            const Text('放号与执行规则',
                style: TextStyle(fontWeight: FontWeight.bold)),
            const Text('默认07:00与提前1天来自历史配置，不代表学校当前规则。修改场馆或规则后请重新确认。'),
            text('提前几天放号', daysBefore,
                validate: (s) => (int.tryParse(s ?? '') ?? -1) >= 0 &&
                        (int.tryParse(s ?? '') ?? 99) <= 14
                    ? null
                    : '填写0至14'),
            text('放号时间（北京时间 HH:mm:ss）', release,
                validate: (s) => timeValid(s) ? null : '请填写有效时间'),
            text('执行时间（北京时间 HH:mm:ss）', execute,
                validate: (s) => timeValid(s) ? null : '请填写有效时间'),
            text('执行窗口（秒）', window,
                validate: (s) => int.tryParse(s ?? '') != null &&
                        int.parse(s!) >= 15 &&
                        int.parse(s) <= 3600
                    ? null
                    : '填写15至3600'),
            text('提前准备（秒）', lead,
                validate: (s) => int.tryParse(s ?? '') != null &&
                        int.parse(s!) >= 0 &&
                        int.parse(s) <= 120
                    ? null
                    : '填写0至120'),
            ] else ...[
              const Text('监控设置', style: TextStyle(fontWeight: FontWeight.bold)),
              const Text('监控期间持续在后台检查，耗电和网络使用会增加。当前满员时继续等待；成功、截止或暂停后结束。所有时间均为北京时间，最长72小时。'),
              Padding(padding: const EdgeInsets.symmetric(vertical:4), child: OutlinedButton.icon(key: const ValueKey('monitor-start'), onPressed: busy ? null : () => pickMonitorTime(true),
                icon: const Icon(Icons.schedule), label: Text('监控开始：${beijingMillis(monitorStartAt).replaceAll(' 北京时间','')}'))),
              Padding(padding: const EdgeInsets.symmetric(vertical:4), child: OutlinedButton.icon(key: const ValueKey('monitor-end'), onPressed: busy ? null : () => pickMonitorTime(false),
                icon: const Icon(Icons.event_busy), label: Text('监控截止：${beijingMillis(monitorEndAt).replaceAll(' 北京时间','')}'))),
              DropdownButtonFormField<int>(key: const ValueKey('monitor-interval'), initialValue: pollSeconds,
                decoration: const InputDecoration(labelText: '检查间隔'),
                items: [10, 30, 60, 120, 300, if (![10,30,60,120,300].contains(pollSeconds)) pollSeconds]
                  .map((s) => DropdownMenuItem(value: s, child: Text('每 $s 秒'))).toList(),
                onChanged: busy ? null : (value) => setState(() => pollSeconds = value!)),
            ],
            text('执行优先级（1最高）', priority,
                validate: (s) => int.tryParse(s ?? '') != null &&
                        int.parse(s!) >= 1 &&
                        int.parse(s) <= 99
                    ? null
                    : '填写1至99'),
            CheckboxListTile(
                contentPadding: EdgeInsets.zero,
                value: confirmed,
                onChanged: busy ? null : (v) => setState(() => confirmed = v!),
                title: Text(monitoring ? '我确认发现空位后自动预约' : '我已核对学校当前放号规则')),
            const Text('保存后自动启用本机计划，可在计划菜单中暂停。'),
            if (error != null)
              Text(error!,
                  style: TextStyle(color: Theme.of(context).colorScheme.error)),
            if (busy) const LinearProgressIndicator(),
            FilledButton(
                onPressed: busy ? null : save, child: const Text('保存到本机')),
          ])));
}
