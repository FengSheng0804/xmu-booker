import 'dart:async';
import 'package:flutter/material.dart';
import '../main_capture.dart';
import 'local_api.dart';
import 'plan_editor.dart';

class LocalHomePage extends StatefulWidget {
  const LocalHomePage({super.key});
  @override
  State<LocalHomePage> createState() => _LocalHomePageState();
}

class _LocalHomePageState extends State<LocalHomePage>
    with WidgetsBindingObserver {
  final api = const LocalApi();
  Json? data;
  int tab = 0;
  bool busy = false;
  String? error;
  Timer? timer;
  static const titles = ['本机预约', '计划与任务', '学校订单', '本机设置'];
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    refresh();
    timer = Timer.periodic(const Duration(seconds: 5), (_) {
      if (!busy) refresh(silent: true);
    });
  }

  @override
  void dispose() {
    timer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      refresh();
    } else if (state == AppLifecycleState.paused) {
      timer?.cancel();
    }
    if (state == AppLifecycleState.resumed) {
      timer?.cancel();
      timer = Timer.periodic(const Duration(seconds: 5), (_) {
        if (!busy) refresh(silent: true);
      });
    }
  }

  Future<void> refresh({bool silent = false}) async {
    try {
      final result = await api.home();
      if (mounted) {
        setState(() {
          data = result;
          if (!silent) error = null;
        });
      }
    } catch (e) {
      if (mounted && !silent) setState(() => error = localError(e));
    }
  }

  Future<void> action(Future<void> Function() fn) async {
    if (busy) return;
    setState(() {
      busy = true;
      error = null;
    });
    try {
      await fn();
      await refresh();
    } catch (e) {
      if (mounted) setState(() => error = localError(e));
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<bool> confirm(String title, String text) async =>
      await showDialog<bool>(
          context: context,
          builder: (c) =>
              AlertDialog(title: Text(title), content: Text(text), actions: [
                TextButton(
                    onPressed: () => Navigator.pop(c, false),
                    child: const Text('取消')),
                FilledButton(
                    onPressed: () => Navigator.pop(c, true),
                    child: const Text('确认'))
              ])) ??
      false;
  List<Json> rows(String key) => ((data?[key] as List?) ?? []).cast<Json>();
  Widget card(String title, List<Widget> children) => Card(
      child: Padding(
          padding: const EdgeInsets.all(18),
          child:
              Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Text(title, style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 12),
            ...children
          ])));
  Future<void> editor([Json? plan]) async {
    final venues = rows('venues');
    if (venues.isEmpty) {
      setState(() => error = '请先在首页同步学校场馆');
      return;
    }
    await Navigator.push(
        context,
        MaterialPageRoute(
            builder: (_) => LocalPlanEditor(venues: venues, plan: plan)));
    await refresh();
  }

  List<Widget> home() {
    final auth = (data?['authorization'] as Map?) ?? {};
    final permissions = (data?['permissions'] as Map?) ?? {};
    final next = rows('tasks').where((t) => t['state'] == 'PENDING').toList()
      ..sort((a, b) => (a['run_at'] as num).compareTo(b['run_at'] as num));
    return [
      card('所有预约都在这台手机运行', [
        const Text('无需项目账号、服务器或卡密。只有手机与学校系统通信，计划和授权留在本机。'),
        Text(auth['has_token'] == true
            ? '本机学校授权：${auth['owner']}'
            : '尚未获取本人学校授权'),
        OutlinedButton(
            onPressed: busy
                ? null
                : () async {
                    await Navigator.push(
                        context,
                        MaterialPageRoute(
                            builder: (_) => const OwnSportsCapturePage()));
                    await refresh();
                  },
            child: const Text('获取或更新本机授权')),
      ]),
      card('执行状态', [
        SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: const Text('允许真实预约'),
            value: data?['execution_enabled'] == true,
            onChanged: busy
                ? null
                : (v) => action(() async {
                      if (!v ||
                          await confirm('开启真实预约？',
                              '已启用计划将在这台手机直接向学校提交。请先核对本人授权、时段与学校放号规则。')) {
                        await api.setExecution(v);
                      }
                    })),
        Text(
            '通知：${permissions['notifications'] == true ? '已允许' : '未允许'} · 精确闹钟：${permissions['exact_alarm'] == true ? '已允许' : '未允许'}'),
        Text(permissions['notifications'] == true &&
                permissions['exact_alarm'] == true
            ? '可安排后台窗口；仍受系统省电限制'
            : '后台条件不完整，请在设置中处理；可在执行窗口内打开App执行'),
        Text(
            '下次执行：${next.isEmpty ? '暂无已安排任务' : beijingMillis(next.first['run_at'])}'),
        const Text('强制停止App后任务不会执行，需重新打开恢复调度。断网或授权失效时会停止并提示。'),
      ]),
      card('学校场馆', [
        FilledButton.tonal(
            onPressed: busy
                ? null
                : () => action(() async {
                      await api.syncCatalog();
                    }),
            child: const Text('同步学校场馆与当前时段')),
        Text('最近同步：${beijingMillis(data?['venues_updated_at'])}'),
        if (rows('venues').isEmpty) const Text('尚无已校验场馆。完成授权后同步。'),
        for (final venue in rows('venues'))
          ListTile(
              contentPadding: EdgeInsets.zero,
              title: Text('${venue['stadium_name']}'),
              subtitle: Text('${venue['project_name']}'),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => editor()),
      ]),
      card('本地消息', [
        if (rows('messages').isEmpty) const Text('暂无执行消息'),
        for (final message in rows('messages').take(10))
          ListTile(
              contentPadding: EdgeInsets.zero,
              title: Text('${message['text']}'),
              subtitle: Text(beijingMillis(message['created_at']))),
      ]),
    ];
  }

  List<Widget> plans() => [
        FilledButton.icon(
            onPressed: busy ? null : () => editor(),
            icon: const Icon(Icons.add),
            label: const Text('新建本机计划')),
        if (rows('plans').isEmpty)
          const Padding(
              padding: EdgeInsets.all(24),
              child: Text('尚无计划。同步场馆后可创建；新计划默认暂停。')),
        for (final p in rows('plans'))
          card('${p['name']}', [
            Text(
                '${p['venue']['stadium_name']} · ${p['repeat'] == 'WEEKLY' ? '每周' : '单次 ${p['date']}'}'),
            Text(
                '执行 ${p['execution_time']} 北京时间 · ${p['enabled'] == true ? '已启用' : '已暂停'}'),
            for (final e in (p['weekdays'] as Map).entries)
              Text('星期${e.key}：${(e.value as List).join(' → ')}'),
            Wrap(spacing: 8, children: [
              OutlinedButton(
                  onPressed: busy ? null : () => editor(p),
                  child: const Text('编辑')),
              OutlinedButton(
                  onPressed: busy
                      ? null
                      : () => editor({...p}
                        ..remove('id')
                        ..['enabled'] = false
                        ..['name'] = '${p['name']} 副本'),
                  child: const Text('复制')),
              TextButton(
                  onPressed: busy
                      ? null
                      : () => action(() async {
                            await api.savePlan(
                                {...p, 'enabled': p['enabled'] != true});
                          }),
                  child: Text(p['enabled'] == true ? '暂停' : '启用')),
              TextButton(
                  onPressed: busy
                      ? null
                      : () => action(() async {
                            if (await confirm('删除计划？',
                                '未提交的任务将取消。已经提交的学校预约不会被取消，历史执行记录保留。')) {
                              await api.deletePlan('${p['id']}');
                            }
                          }),
                  child: const Text('删除')),
            ]),
          ]),
        const Padding(
            padding: EdgeInsets.all(12),
            child: Text('执行记录', style: TextStyle(fontWeight: FontWeight.bold))),
        for (final t in rows('tasks'))
          Card(
              child: ListTile(
                  title: Text('${stateText(t['state'])} · ${t['date']}'),
                  subtitle: Text(
                      '${t['target']['stadium_name']}\n${beijingMillis(t['run_at'])}\n${t['reason']}'),
                  isThreeLine: true,
                  onTap: () => showTask(t))),
      ];
  void showTask(Json task) {
    showModalBottomSheet<void>(
        context: context,
        isScrollControlled: true,
        builder: (c) => SafeArea(
            child: SingleChildScrollView(
                padding: const EdgeInsets.all(24),
                child: Column(
                    mainAxisSize: MainAxisSize.min,
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Text(stateText(task['state']),
                          style: Theme.of(c).textTheme.titleLarge),
                      Text('入场日 ${task['date']}'),
                      Text('${task['target']['stadium_name']}'),
                      Text(
                          '候选：${(task['target']['ranges'] as List).join(' → ')}'),
                      Text('执行：${beijingMillis(task['run_at'])}'),
                      Text('截止：${beijingMillis(task['deadline'])}'),
                      Text('${task['reason']}'),
                      const Text('结果未知时只核对学校订单，不会再次提交。'),
                      if (['PENDING', 'UNKNOWN', 'SENT', 'CLAIMED']
                          .contains(task['state']))
                        FilledButton(
                            onPressed: busy
                                ? null
                                : () {
                                    Navigator.pop(c);
                                    action(() => api.runTask('${task['id']}'));
                                  },
                            child: Text(task['state'] == 'PENDING'
                                ? '在执行窗口内运行'
                                : '只查单核对')),
                    ]))));
  }

  List<Widget> orders() => [
        Text('本机缓存 ${rows('orders').length} 条学校订单'),
        FilledButton.tonal(
            onPressed: busy
                ? null
                : () => action(() async {
                      await api.syncOrders();
                    }),
            child: const Text('从学校同步本人订单')),
        Text('最近同步：${beijingMillis(data?['orders_updated_at'])}'),
        const Text('以下为本机缓存，需同步取得当前状态。取消预约或学校自身的缴费事项，请前往学校原入口处理。'),
        if (rows('orders').isEmpty)
          const Padding(
              padding: EdgeInsets.all(24), child: Text('尚未同步订单，或学校未返回本人订单。')),
        for (final order in rows('orders'))
          card(
              '${order['stadium_name'] ?? '学校预约'} · ${order['audit_status_text'] ?? '查看详情'}',
              [
                Text('${order['project_name'] ?? ''}'),
                for (final d in ((order['detail'] as List?) ?? []).cast<Map>())
                  Text(
                      '${d['date']} ${d['interval_time']} · ${d['area_name']} · ${d['status_text'] ?? ''}'),
                SelectableText('学校订单号：${order['order_id']}'),
              ]),
      ];
  List<Widget> settings() {
    final permissions = (data?['permissions'] as Map?) ?? {};
    return [
      card('后台执行条件', [
        for (final (kind, label, key) in [
          ('notifications', '通知权限', 'notifications'),
          ('alarm', '精确闹钟权限', 'exact_alarm'),
          ('battery', '电池优化设置', 'battery_unrestricted')
        ])
          ListTile(
              contentPadding: EdgeInsets.zero,
              title: Text(label),
              subtitle: Text(permissions[key] == true ? '已具备' : '需检查系统设置'),
              trailing: const Icon(Icons.open_in_new),
              onTap: busy ? null : () => action(() => api.settings(kind))),
        const Text('系统可能延迟或终止后台任务。请允许本App后台运行；强制停止后重新打开。执行窗口内可以从任务详情手动运行。'),
      ]),
      card('授权与隐私', [
        const Text(
            '学校授权仅在 Android Keystore 保护下保存在本机，不上传给项目服务器。卸载或清除数据会失去授权和计划。'),
        OutlinedButton(
            onPressed: busy
                ? null
                : () async {
                    await Navigator.push(
                        context,
                        MaterialPageRoute(
                            builder: (_) => const OwnSportsCapturePage()));
                    await refresh();
                  },
            child: const Text('管理本机学校授权')),
        TextButton(
            onPressed: busy
                ? null
                : () => action(() async {
                      if (await confirm('清除全部本地数据？',
                          '将暂停预约，删除本机授权、计划、执行记录、订单缓存和消息；学校已有订单不会取消。')) {
                        await api.clear();
                      }
                    }),
            child: const Text('暂停并清除全部本地数据')),
      ]),
      card('开源独立版', [
        const Text('单个本人账号 · 免费 · GPL-3.0\n不提供服务器托管、卡密兑换或任何项目收费入口。'),
        const Text('场馆映射沿用当前已实现的思明、翔安健身房；放号规则须以学校当前信息为准。'),
        TextButton(
            onPressed: () => showLicensePage(
                context: context,
                applicationName: '厦大体育预约',
                applicationLegalese: 'GPL-3.0 · 第三方组件按各自许可分发'),
            child: const Text('开源许可')),
      ]),
    ];
  }

  @override
  Widget build(BuildContext context) => Scaffold(
      appBar: AppBar(title: Text(titles[tab]), actions: [
        IconButton(
            onPressed: busy ? null : () => refresh(),
            icon: const Icon(Icons.refresh),
            tooltip: '刷新本机状态')
      ]),
      body: SafeArea(
          child: Column(children: [
        if (busy) const LinearProgressIndicator(),
        if (error != null)
          MaterialBanner(content: Text(error!), actions: [
            TextButton(
                onPressed: () => setState(() => error = null),
                child: const Text('关闭')),
            TextButton(
                onPressed: busy ? null : () => refresh(),
                child: const Text('重试'))
          ]),
        Expanded(
            child: data == null
                ? Center(
                    child: error == null
                        ? const CircularProgressIndicator()
                        : const Text('无法读取本机状态，请重试'))
                : RefreshIndicator(
                    onRefresh: refresh,
                    child:
                        ListView(padding: const EdgeInsets.all(16), children: [
                      ...[home, plans, orders, settings][tab](),
                      const SizedBox(height: 24)
                    ]))),
      ])),
      bottomNavigationBar: NavigationBar(
          selectedIndex: tab,
          onDestinationSelected: (i) => setState(() => tab = i),
          destinations: const [
            NavigationDestination(icon: Icon(Icons.home_outlined), label: '首页'),
            NavigationDestination(icon: Icon(Icons.event_note), label: '计划'),
            NavigationDestination(icon: Icon(Icons.receipt_long), label: '订单'),
            NavigationDestination(
                icon: Icon(Icons.settings_outlined), label: '设置')
          ]));
}
