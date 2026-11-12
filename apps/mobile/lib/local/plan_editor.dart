import 'package:flutter/material.dart';
import 'local_api.dart';

class LocalPlanEditor extends StatefulWidget {
  const LocalPlanEditor({super.key, required this.venues, this.plan});
  final List<Json> venues;
  final Json? plan;
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
  late DateTime date;
  String repeat = 'ONCE';
  bool confirmed = false, enabled = false, busy = false;
  String? error;
  List<Json> slots = [];
  @override
  void initState() {
    super.initState();
    final p = widget.plan;
    venue = p?['venue'] as Json? ?? widget.venues.first;
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
      enabled = p['enabled'] == true;
      final days = p['weekdays'] as Map;
      for (var i = 0; i < 7; i++) {
        ranges[i].text = ((days['${i + 1}'] as List?) ?? []).join(', ');
      }
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

  String get day => date.toIso8601String().substring(0, 10);
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
    });
    try {
      final data = await const LocalApi().availability({...venue, 'date': day});
      if (mounted) {
        setState(() => slots = (data['time_slot_list'] as List)
            .cast<Json>()
            .where((r) => r['date'] == day)
            .toList());
      }
    } catch (e) {
      if (mounted) setState(() => error = localError(e));
    } finally {
      if (mounted) setState(() => busy = false);
    }
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
    if (enabled && !confirmed) {
      setState(() => error = '启用前请确认学校放号规则');
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
        'date': day,
        'weekdays': weekly,
        'release_time': release.text,
        'execution_time': execute.text,
        'days_before': int.parse(daysBefore.text),
        'window_seconds': int.parse(window.text),
        'prepare_lead_seconds': int.parse(lead.text),
        'priority': int.parse(priority.text),
        'rule_confirmed': confirmed,
        'enabled': enabled
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
            const Text('计划只保存在这台手机。学校放号规则须自行核对；新建计划默认暂停。'),
            text('计划名称', name),
            DropdownButtonFormField<String>(
                initialValue: venue['venue_id'].toString(),
                decoration: const InputDecoration(labelText: '场馆'),
                items: widget.venues
                    .map((v) => DropdownMenuItem(
                        value: v['venue_id'].toString(),
                        child: Text('${v['stadium_name']}')))
                    .toList(),
                onChanged: busy
                    ? null
                    : (v) => setState(() => venue = widget.venues.firstWhere(
                        (item) => item['venue_id'].toString() == v))),
            DropdownButtonFormField<String>(
                initialValue: repeat,
                decoration: const InputDecoration(labelText: '计划类型'),
                items: const [
                  DropdownMenuItem(value: 'ONCE', child: Text('单次')),
                  DropdownMenuItem(value: 'WEEKLY', child: Text('每周'))
                ],
                onChanged: busy ? null : (v) => setState(() => repeat = v!)),
            ListTile(
                contentPadding: EdgeInsets.zero,
                title: Text('入场日期：$day（星期${weekdays[date.weekday - 1]}）'),
                trailing: const Icon(Icons.calendar_month),
                onTap: busy
                    ? null
                    : () async {
                        final today = beijingToday();
                        final picked = await showDatePicker(
                            context: context,
                            initialDate: date.isBefore(today) ? today : date,
                            firstDate: today,
                            lastDate: today.add(const Duration(days: 365)));
                        if (picked != null) setState(() => date = picked);
                      }),
            OutlinedButton(
                onPressed: busy ? null : query,
                child: const Text('查询该日学校时段与余量')),
            if (slots.isNotEmpty) ...[
              const Text('点击学校返回的时段，添加到当前选择的入场星期；逗号分隔，顺序即优先级。'),
              Wrap(
                  spacing: 8,
                  children: slots
                      .map((s) => ActionChip(
                          label: Text('${s['time_range']}'),
                          onPressed: busy
                              ? null
                              : () {
                                  final c = ranges[date.weekday - 1];
                                  final r = '${s['time_range']}';
                                  if (!c.text
                                      .split(',')
                                      .map((e) => e.trim())
                                      .contains(r)) {
                                    setState(() => c.text =
                                        c.text.isEmpty ? r : '${c.text}, $r');
                                  }
                                }))
                      .toList())
            ],
            for (var i = 0; i < 7; i++)
              if (repeat == 'WEEKLY' || i == date.weekday - 1)
                text('星期${weekdays[i]}候选时段', ranges[i],
                    hint: '最多3个，例如 08:00-09:00, 09:00-10:00', validate: (s) {
                  if ((s ?? '').trim().isEmpty) return null;
                  final r =
                      s!.split(RegExp(r'[,，]')).map((e) => e.trim()).toList();
                  if (r.length > 3 || r.toSet().length != r.length) {
                    return '最多3个且不能重复';
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
                title: const Text('我已核对学校当前放号规则')),
            SwitchListTile(
                contentPadding: EdgeInsets.zero,
                value: enabled,
                onChanged: busy ? null : (v) => setState(() => enabled = v),
                title: const Text('启用本机计划'),
                subtitle: const Text('仍需在首页打开真实预约总开关')),
            if (error != null)
              Text(error!,
                  style: TextStyle(color: Theme.of(context).colorScheme.error)),
            if (busy) const LinearProgressIndicator(),
            FilledButton(
                onPressed: busy ? null : save, child: const Text('保存到本机')),
          ])));
}
