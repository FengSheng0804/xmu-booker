import 'dart:async';
import 'package:flutter/material.dart';
import '../main_capture.dart';
import 'local_api.dart';
import 'plan_editor.dart';
import 'venue_catalog.dart';
import 'entry_pass.dart';

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
  String? selectedCampus;
  int visibleOrders = 10, visibleMessages = 10;
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

  Future<bool> confirm(String title, String text,
          {String confirmText = '确认'}) async =>
      await showDialog<bool>(
          context: context,
          builder: (c) =>
              AlertDialog(title: Text(title), content: Text(text), actions: [
                TextButton(
                    onPressed: () => Navigator.pop(c, false),
                    child: const Text('取消')),
                FilledButton(
                    onPressed: () => Navigator.pop(c, true),
                    child: Text(confirmText))
              ])) ??
      false;
  List<Json> rows(String key) => ((data?[key] as List?) ?? []).cast<Json>();

  bool loadMore(ScrollNotification notification) {
    if (notification.depth != 0 || notification is! ScrollUpdateNotification ||
        (notification.scrollDelta ?? 0) <= 0 || notification.metrics.extentAfter > 100) {
      return false;
    }
    if (tab == 2 && visibleOrders < rows('orders').length) {
      setState(() => visibleOrders += 10);
    } else if (tab == 0 && visibleMessages < rows('messages').length) {
      setState(() => visibleMessages += 10);
    }
    return false;
  }

  Widget pageHint(String kind, int visible, int total) => Padding(
      key: ValueKey('$kind-page-footer'), padding: const EdgeInsets.all(12),
      child: Text(visible < total ? '继续下滑加载更多${kind == 'orders' ? '订单' : '消息'}（已显示 $visible/$total）'
          : '已显示全部${kind == 'orders' ? '订单' : '消息'}'));

  Future<void> deleteRecord(Json task) async {
    if (busy) return;
    if (await confirm('删除执行记录？', '该记录将从执行记录列表移除，学校已有预约不会取消。', confirmText: '删除')) {
      await action(() => api.deleteExecutionRecord('${task['id']}'));
    }
  }

  Future<void> planAction(String actionName, Json plan) async {
    if (actionName == 'edit') { await editor(plan); return; }
    if (actionName == 'copy') {
      await editor({...plan}..remove('id')..['enabled'] = false..['name'] = '${plan['name']} 副本');
      return;
    }
    if (actionName == 'toggle') {
      await action(() => api.savePlan({...plan, 'enabled': plan['enabled'] != true}));
    } else if (actionName == 'delete' && await confirm('删除计划？',
        '未提交的任务将取消。已经提交的学校预约不会被取消，历史执行记录保留。')) {
      await action(() => api.deletePlan('${plan['id']}'));
    }
  }

  void showPlan(Json plan) {
    showModalBottomSheet<void>(context: context, isScrollControlled: true,
      builder: (c) => SafeArea(child: SingleChildScrollView(padding: const EdgeInsets.all(24),
        child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Text('${plan['name']}', style: Theme.of(c).textTheme.titleLarge),
          Text('${plan['venue']['stadium_name']} · ${planStatus(plan)}'),
          Text('${plan['repeat'] == 'WEEKLY' ? '每周' : '入场日 ${plan['date']}'} · 执行 ${plan['execution_time']} 北京时间'),
          for (final entry in (plan['weekdays'] as Map).entries)
            Text('星期${entry.key}：${(entry.value as List).join(' → ')}'),
          if (plan['last_result'] is Map)
            Text('最近执行：${stateText(plan['last_result']['state'])} · ${executionReason(plan['last_result']['reason'])}'),
          if (singlePlanEnded(plan)) const Text('单次计划已结束，重新预约请复制计划或修改入场日期'),
          FilledButton(onPressed: () { Navigator.pop(c); editor(plan); }, child: const Text('编辑计划')),
        ]))));
  }
  Widget card(String title, List<Widget> children) => Card(
      child: Padding(
          padding: const EdgeInsets.all(18),
          child:
              Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Text(title, style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 12),
            ...children
          ])));
  Future<void> editor([Json? plan, Json? initialVenue]) async {
    final venues = rows('venues');
    if (venues.isEmpty) {
      setState(() => error = '请先在首页同步学校场馆');
      return;
    }
    await Navigator.push(
        context,
        MaterialPageRoute(
            builder: (_) => LocalPlanEditor(
                venues: venues, plan: plan, initialVenue: initialVenue)));
    await refresh();
  }

  List<Widget> home() {
    final venues = rows('venues');
    final campuses = venueCampuses(venues);
    final campus = campuses.contains(selectedCampus)
        ? selectedCampus
        : campuses.firstOrNull;
    final auth = (data?['authorization'] as Map?) ?? {};
    final permissions = (data?['permissions'] as Map?) ?? {};
    final next = rows('tasks').where((t) => t['state'] == 'PENDING').toList()
      ..sort((a, b) => (a['run_at'] as num).compareTo(b['run_at'] as num));
    final ready = auth['has_token'] == true && data?['execution_enabled'] == true &&
        permissions['notifications'] == true && permissions['exact_alarm'] == true;
    return [
      card('所有预约都在这台手机运行', [
        const Text('无需项目账号、服务器。只有手机与学校系统通信，计划和授权留在本机。'),
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
        ListTile(contentPadding: EdgeInsets.zero,
          leading: Icon(ready ? Icons.check_circle_outline : Icons.info_outline,
            color: ready ? Theme.of(context).colorScheme.primary : Theme.of(context).colorScheme.error),
          title: Text(!ready ? '需要完善执行条件' : next.isEmpty ? '暂无待执行计划' : '已安排自动执行'),
          subtitle: Text(!ready ? '请检查学校授权和后台权限' : next.isEmpty ? '创建计划后将自动启用' :
            '${next.first['target']['stadium_name']}\n${beijingMillis(next.first['run_at'])}'),
        ),
        Wrap(spacing: 8, runSpacing: 4, children: [
          Chip(label: Text('待执行 ${next.length} 项')),
          Chip(label: Text(permissions['keep_alive_active'] == true ? '后台守护运行中' : '后台守护待命')),
        ]),
        ExpansionTile(key: const PageStorageKey('execution-conditions'), tilePadding: EdgeInsets.zero, title: const Text('查看执行条件'), children: [
          for (final (label, key) in [('通知', 'notifications'), ('精确闹钟', 'exact_alarm'), ('不受电池优化限制', 'battery_unrestricted')])
            ListTile(dense: true, title: Text(label), trailing: Icon(permissions[key] == true ? Icons.check : Icons.warning_amber)),
          const Text('保存后自动启用，可在计划菜单暂停。强制停止后需重新打开；断网或授权失效时会提示。'),
          TextButton(onPressed: () => setState(() => tab = 3), child: const Text('管理后台权限')),
        ]),
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
        if (campus != null)
          DropdownButtonFormField<String>(
            key: ValueKey('home-campus-$campus'),
            initialValue: campus,
            isExpanded: true,
            decoration: const InputDecoration(labelText: '校区'),
            items: campuses
                .map((c) => DropdownMenuItem(value: c, child: Text(c)))
                .toList(),
            onChanged: busy ? null : (c) => setState(() => selectedCampus = c),
          ),
        for (final venue in venues.where((v) => venueCampus(v) == campus))
          ListTile(
              contentPadding: EdgeInsets.zero,
              title: Text('${venue['stadium_name']}'),
              subtitle: Text('${venue['project_name']}'),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => editor(null, venue)),
      ]),
      card('本地消息', [
        if (rows('messages').isEmpty) const Text('暂无执行消息'),
        for (final message in rows('messages').take(visibleMessages))
          ListTile(
              contentPadding: EdgeInsets.zero,
              title: Text('${message['text']}'),
              subtitle: Text(beijingMillis(message['created_at']))),
        if (rows('messages').isNotEmpty) pageHint('messages', visibleMessages.clamp(0, rows('messages').length), rows('messages').length),
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
              child: Text('尚无计划。同步场馆后可创建，保存后自动启用。')),
        for (final p in rows('plans'))
          Card(key: ValueKey('plan-card-${p['id']}'), child: ListTile(
            title: Text('${p['name']}', maxLines: 1, overflow: TextOverflow.ellipsis),
            subtitle: Text('${p['venue']['stadium_name']}\n'
                '${p['repeat'] == 'WEEKLY' ? '每周' : p['date']} · ${p['execution_time']} · '
                '${planStatus(p).replaceAll('预约成功（学校订单已确认）', '预约成功').replaceAll('需要核对，禁止重发', '待核对')}',
                maxLines: 3, overflow: TextOverflow.ellipsis),
            onTap: () => showPlan(p),
            trailing: PopupMenuButton<String>(tooltip: '计划操作', enabled: !busy,
              onSelected: (value) => planAction(value, p),
              itemBuilder: (_) => [
                const PopupMenuItem(value: 'edit', child: Text('编辑')),
                const PopupMenuItem(value: 'copy', child: Text('复制')),
                if (!singlePlanEnded(p)) PopupMenuItem(value: 'toggle', child: Text(p['enabled'] == true ? '暂停' : '启用')),
                const PopupMenuItem(value: 'delete', child: Text('删除')),
              ]))),
        const Padding(
            padding: EdgeInsets.all(12),
            child: Text('执行记录', style: TextStyle(fontWeight: FontWeight.bold))),
        for (final t in rows('tasks'))
          Card(key: ValueKey('execution-record-${t['id']}'),
              child: ListTile(
                  title: Text('${stateText(t['state'])} · ${t['date']}'),
                  subtitle: Text(
                      '${t['target']['stadium_name']}\n${beijingMillis(t['run_at'])}\n${executionReason(t['reason'])}'),
                  isThreeLine: true,
                  trailing: !['PENDING', 'CLAIMED', 'SENT'].contains(t['state'])
                      ? IconButton(tooltip: '删除执行记录', icon: const Icon(Icons.delete_outline),
                          onPressed: busy ? null : () => deleteRecord(t)) : null,
                  onTap: () => showTask(t))),
      ];
  Future<void> reviewTask(String id) => action(() async {
        final checked = await api.inspectTask(id);
        if (!mounted) return;
        if (checked['can_release'] == true) {
          final target = checked['target'] as Map;
          final approved = await confirm(
              '确认学校无订单后解除阻塞',
              '已完整读取本人学校订单，未找到对应预约。\n'
                  '${target['stadium_name']} · ${checked['date']}\n'
                  '候选：${(target['ranges'] as List).join(' → ')}\n\n'
                  '请同时核对学校原入口没有对应订单（含待支付订单）。'
                  '确认后只结束这条旧任务，不会重新预约。',
              confirmText: '已核对无订单，解除阻塞');
          if (!approved) return;
          final resolved = await api.releaseTask(id);
          if (!mounted) return;
          ScaffoldMessenger.of(context).showSnackBar(
              SnackBar(content: Text(executionReason(resolved['reason']))));
        } else {
          ScaffoldMessenger.of(context).showSnackBar(
              SnackBar(content: Text(executionReason(checked['reason']))));
        }
      });

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
                      Text(executionReason(task['reason'])),
                      if (task['state'] == 'SUCCEEDED') TextButton(onPressed: () {
                        Navigator.pop(c); setState(() => tab = 2);
                      }, child: const Text('前往学校订单查看入场码')),
                      const Text('结果未知时只核对学校订单，不会再次提交。'),
                      if ('${task['reason'] ?? ''}'
                          .contains('PENDING_RECONCILIATION')) ...[
                        const SizedBox(height: 12),
                        if (((task['blocking_tasks'] as List?) ?? []).isEmpty)
                          const Text('旧任务阻塞已解除；此计划未执行，需要预约时请另建计划。'),
                        for (final old
                            in ((task['blocking_tasks'] as List?) ?? [])
                                .cast<Map>())
                          if (old['state'] == 'UNKNOWN')
                            OutlinedButton(
                              onPressed: busy
                                  ? null
                                  : () {
                                      Navigator.pop(c);
                                      reviewTask('${old['id']}');
                                    },
                              child: Text('核对并处理旧任务（${old['date']}）'),
                            )
                          else
                            const Text('旧任务仍在执行，请稍后刷新再核对。'),
                      ],
                      if (['SENT', 'CLAIMED'].contains(task['state']))
                        const Text('任务正在执行，待结果返回后再核对。'),
                      if (['PENDING', 'UNKNOWN'].contains(task['state']))
                        FilledButton(
                            onPressed: busy
                                ? null
                                : () {
                                    Navigator.pop(c);
                                    if (task['state'] == 'UNKNOWN') {
                                      reviewTask('${task['id']}');
                                    } else {
                                      action(
                                          () => api.runTask('${task['id']}'));
                                    }
                                  },
                            child: Text(task['state'] == 'PENDING'
                                ? '在执行窗口内运行'
                                : '查单并处理阻塞')),
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
        for (final order in rows('orders').take(visibleOrders))
          Card(key: ValueKey('order-${order['order_id']}'), child: ListTile(
            title: Text('${order['stadium_name'] ?? '学校预约'} · ${order['audit_status_text'] ?? '查看详情'}'),
            subtitle: Text([for (final d in ((order['detail'] as List?) ?? []).cast<Map>())
              '${d['date']} ${d['interval_time']} · ${d['status_text'] ?? ''}'].join('\n'), maxLines: 2, overflow: TextOverflow.ellipsis),
            trailing: const Icon(Icons.chevron_right), onTap: () => showOrder(order))),
        if (rows('orders').isNotEmpty) pageHint('orders', visibleOrders.clamp(0, rows('orders').length), rows('orders').length),
      ];
  void showOrder(Json order) {
    showModalBottomSheet<void>(context: context, isScrollControlled: true,
      builder: (c) => SafeArea(child: SingleChildScrollView(padding: const EdgeInsets.all(24),
        child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Text('${order['stadium_name'] ?? '学校预约'}', style: Theme.of(c).textTheme.titleLarge),
          Text('${order['project_name'] ?? ''} · ${order['audit_status_text'] ?? ''}'),
          for (final d in ((order['detail'] as List?) ?? []).cast<Map>())
            Text('${d['date']} ${d['interval_time']} · ${d['area_name']} · ${d['status_text'] ?? ''}'),
          SelectableText('学校订单号：${order['order_id']}'),
          EntryPassView(orderId: '${order['order_id']}'),
        ]))));
  }
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
        Text(permissions['keep_alive_active'] == true ? '后台守护运行中，通知栏可查看。' : '有待执行计划且权限齐全时，自动启动后台守护。'),
        const Text('请在手机系统中允许后台运行与自启动。系统强制停止后需重新打开恢复。'),
      ]),
      card('存储管理', [
        const Text('自动清理旧版调试资源和诊断临时文件，保留学校授权、计划、订单和执行历史。'),
        OutlinedButton(onPressed: busy ? null : () => action(() async {
          final bytes = await api.cleanStorage();
          if (mounted) {
            ScaffoldMessenger.of(context).showSnackBar(SnackBar(
              content: Text('已释放 ${(bytes / 1048576).toStringAsFixed(1)} MB')));
          }
        }), child: const Text('清理临时文件')),
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
                applicationName: '场馆预约助手',
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
                    child: NotificationListener<ScrollNotification>(onNotification: loadMore,
                      child: Builder(builder: (_) {
                        final children = [...[home, plans, orders, settings][tab](), const SizedBox(height: 24)];
                        return ListView.builder(key: PageStorageKey('local-tab-$tab'),
                          padding: const EdgeInsets.all(16), itemCount: children.length,
                          itemBuilder: (_, index) => children[index]);
                      })))),
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
