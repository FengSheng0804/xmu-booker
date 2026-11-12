import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:xmu_sports_booking/main.dart';
import 'package:xmu_sports_booking/main_capture.dart';
import 'package:xmu_sports_booking/local/local_api.dart';
import 'package:xmu_sports_booking/local/plan_editor.dart';
import 'package:xmu_sports_booking/local/venue_catalog.dart';
import 'package:xmu_sports_booking/local/slot_option.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final calls = <MethodCall>[];
  final venuesList = <Map<String, dynamic>>[
    {
      'stadium_id': '16',
      'venue_id': '14',
      'category_id': '8',
      'stadium_name': '翔安校区健身房',
      'project_name': '健身房'
    },
    {
      'stadium_id': '13',
      'venue_id': '13',
      'category_id': '3',
      'stadium_name': '翔安校区羽毛球场',
      'project_name': '羽毛球'
    },
    {
      'stadium_id': '8',
      'venue_id': '8',
      'category_id': '9',
      'stadium_name': '思明校区东苑网球场',
      'project_name': '网球'
    },
  ];
  late Json home;
  Json? resolvedVenue;
  setUp(() {
    resolvedVenue = null;
    calls.clear();
    home = {
      'authorization': {'has_token': true, 'owner': '12****78'},
      'execution_enabled': false,
      'permissions': {
        'notifications': false,
        'exact_alarm': false,
        'battery_unrestricted': false
      },
      'plans': [],
      'tasks': [],
      'orders': [],
      'venues': venuesList,
      'messages': []
    };
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(LocalApi.channel, (call) async {
      calls.add(call);
      if (call.method == 'home') return jsonEncode(home);
      if (call.method == 'savePlan') return 'saved-id';
      if (call.method == 'availability') {
        return jsonEncode({'time_slot_list': [],
          if (resolvedVenue != null) 'resolved_target': resolvedVenue});
      }
      return null;
    });
  });
  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(LocalApi.channel, null);
  });
  test('ports never choose remote destinations', () {
    expect(localDebugPort('127.0.0.1:39999'), 39999);
    expect(localDebugPort('39999'), 39999);
    for (final value in ['host:39999', '0', '65536', '10.999.1.1:39999']) {
      expect(localDebugPort(value), isNull);
    }
  });
  test('campus classification includes legacy names and preserves unknowns',
      () {
    for (final id in ['2', '4', '5', '6', '8', '9', '11', '17']) {
      expect(venueCampus({'stadium_id': id}), '思明校区');
    }
    for (final id in ['12', '13', '14', '15', '16']) {
      expect(venueCampus({'stadium_id': id}), '翔安校区');
    }
    expect(venueCampus({'stadium_name': '翔安校区新馆'}), '翔安校区');
    expect(venueCampus({'stadium_id': '999'}), '其他校区（待确认）');
  });
  testWidgets('campus filters editor venues and query uses the new selection',
      (tester) async {
    await tester
        .pumpWidget(MaterialApp(home: LocalPlanEditor(venues: venuesList)));
    await tester.pumpAndSettle();
    List<String?> venueIds() => tester
        .widget<DropdownButton<String>>(find.descendant(
            of: find.byKey(const ValueKey('plan-venue-翔安校区-16_14_8')),
            matching: find.byType(DropdownButton<String>)))
        .items!
        .map((item) => item.value)
        .toList();
    expect(venueIds(), ['16_14_8', '13_13_3']);
    await tester.tap(find.byKey(const ValueKey('plan-campus')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('思明校区').last);
    await tester.pumpAndSettle();
    final field = tester.widget<DropdownButtonFormField<String>>(
        find.byKey(const ValueKey('plan-venue-思明校区-8_8_9')));
    final dropdown = tester.widget<DropdownButton<String>>(find.descendant(
        of: find.byKey(const ValueKey('plan-venue-思明校区-8_8_9')),
        matching: find.byType(DropdownButton<String>)));
    expect(dropdown.items!.map((item) => item.value), ['8_8_9']);
    expect(field.initialValue, '8_8_9');
    await tester.ensureVisible(find.text('查询学校时段与余量'));
    await tester.tap(find.text('查询学校时段与余量'));
    await tester.pumpAndSettle();
    final query = calls.singleWhere((c) => c.method == 'availability');
    final target = jsonDecode((query.arguments as Map)['target'] as String);
    expect(target['stadium_id'], '8');
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  });
  testWidgets('home campus filter opens editor with the selected venue',
      (tester) async {
    await tester.pumpWidget(const XmuApp());
    await tester.pumpAndSettle();
    await tester.scrollUntilVisible(
        find.byKey(const ValueKey('home-campus-翔安校区')), 250);
    await tester.drag(find.byType(Scrollable).first, const Offset(0, -200));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('home-campus-翔安校区')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('思明校区').last);
    await tester.pumpAndSettle();
    expect(find.text('翔安校区健身房'), findsNothing);
    await tester.ensureVisible(find.text('思明校区东苑网球场'));
    await tester.tap(find.text('思明校区东苑网球场'));
    await tester.pumpAndSettle();
    final field = tester.widget<DropdownButtonFormField<String>>(
        find.byKey(const ValueKey('plan-venue-思明校区-8_8_9')));
    expect(field.initialValue, '8_8_9');
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  });
  test('timestamps use Beijing regardless of host timezone', () {
    expect(beijingMillis(1794441600000), contains('北京时间'));
    expect(beijingMillis(0), '尚未更新');
  });
  test('school rejection description survives native channel mapping', () {
    const detail = '学校拒绝请求（步骤：选择时段校验，状态：0）：预约次数已达上限';
    expect(
        localError(PlatformException(code: 'SCHOOL_REJECTED', message: detail)),
        detail);
    expect(planStatus({'repeat': 'WEEKLY', 'enabled': true}), '已启用');
  });

  test('read authorization failures explain recovery and preserve task history', () {
    const detail = '学校授权校验未通过（步骤：建立体育会话，状态：0）：学校未提供详细原因。请更新授权';
    expect(localError(PlatformException(code: 'READ_CHECK_LOGIN_REJECTED', message: detail)), detail);
    expect(localError(PlatformException(code: 'READ_CHECK_REJECTED')), contains('更新学校授权'));
    expect(localError(PlatformException(code: 'READ_CHECK_REJECTED')), contains('待核对记录会保留'));
    expect(localError(PlatformException(code: 'READ_CHECK_HTTP_FAILED')), contains('网络'));
    expect(localError(PlatformException(code: 'READ_CHECK_LOGIN_REJECTED', message: 'untrusted-response')),
        isNot(contains('untrusted-response')));
  });

  Json unknownTask() => {
        'id': 'old-outside-history',
        'state': 'UNKNOWN',
        'date': '2026-11-13',
        'run_at': 1,
        'deadline': 2,
        'reason': '需要核对',
        'target': {
          ...venuesList.first,
          'ranges': ['12:00-14:00']
        },
      };

  testWidgets(
      'blocked task exposes old record and cancelling never releases or retries',
      (tester) async {
    final old = unknownTask();
    home['tasks'] = [
      {
        ...old,
        'id': 'new-blocked',
        'state': 'FAILED',
        'reason': '预约未完成：PENDING_RECONCILIATION',
        'blocking_tasks': [old]
      }
    ];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(LocalApi.channel, (call) async {
      calls.add(call);
      if (call.method == 'home') return jsonEncode(home);
      if (call.method == 'inspectTask') {
        return jsonEncode({...old, 'can_release': true});
      }
      return null;
    });
    await tester.pumpWidget(const XmuApp());
    await tester.pumpAndSettle();
    await tester.tap(find.text('计划').last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('未完成 · 2026-11-13'));
    await tester.pumpAndSettle();
    final handle = find.text('核对并处理旧任务（2026-11-13）');
    await tester.ensureVisible(handle);
    await tester.tap(handle);
    await tester.pump(const Duration(milliseconds: 400));
    expect(find.text('确认学校无订单后解除阻塞'), findsOneWidget);
    await tester.tap(find.text('取消'));
    await tester.pumpAndSettle();
    expect(
        (calls.singleWhere((c) => c.method == 'inspectTask').arguments
            as Map)['id'],
        'old-outside-history');
    expect(calls.where((c) => ['releaseTask', 'runTask'].contains(c.method)),
        isEmpty);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets(
      'release requires reviewed lookup and explicit confirmation, never submits',
      (tester) async {
    final old = unknownTask();
    home['tasks'] = [old];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(LocalApi.channel, (call) async {
      calls.add(call);
      if (call.method == 'home') return jsonEncode(home);
      if (call.method == 'inspectTask') {
        return jsonEncode({...old, 'can_release': true});
      }
      if (call.method == 'releaseTask') {
        final resolved = {
          ...old,
          'state': 'FAILED',
          'reason': '旧任务阻塞已解除；未重新提交'
        };
        home['tasks'] = [resolved];
        return jsonEncode(resolved);
      }
      return null;
    });
    await tester.pumpWidget(const XmuApp());
    await tester.pumpAndSettle();
    await tester.tap(find.text('计划').last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('需要核对，禁止重发 · 2026-11-13'));
    await tester.pumpAndSettle();
    final lookup = find.text('查单并处理阻塞');
    await tester.ensureVisible(lookup);
    await tester.tap(lookup);
    await tester.pump(const Duration(milliseconds: 400));
    expect(calls.where((c) => c.method == 'releaseTask'), isEmpty);
    await tester.tap(find.text('已核对无订单，解除阻塞'));
    await tester.pumpAndSettle();
    final released = calls.singleWhere((c) => c.method == 'releaseTask');
    expect((released.arguments as Map)['confirmed_no_order'], isTrue);
    expect(calls.where((c) => c.method == 'runTask'), isEmpty);
    await tester.pumpWidget(const SizedBox.shrink());
  });
  testWidgets('finished single plan shows failure and cannot appear enabled',
      (tester) async {
    home['plans'] = [
      {
        'id': 'finished-plan',
        'name': '单次测试',
        'repeat': 'ONCE',
        'date': '2026-11-13',
        'venue': venuesList.first,
        'execution_time': '17:40:30',
        'enabled': false,
        'weekdays': {
          '5': ['12:00-14:00']
        },
        'last_result': {
          'date': '2026-11-13',
          'state': 'FAILED',
          'reason': '学校拒绝请求（步骤：选择时段校验，状态：0）：预约次数已达上限'
        },
      }
    ];
    await tester.pumpWidget(const XmuApp());
    await tester.pumpAndSettle();
    await tester.tap(find.text('计划').last);
    await tester.pumpAndSettle();
    expect(find.textContaining('已结束（未完成）'), findsOneWidget);
    expect(find.textContaining('最近执行：未完成'), findsNothing);
    await tester.tap(find.byKey(const ValueKey('plan-card-finished-plan')));
    await tester.pumpAndSettle();
    expect(find.textContaining('最近执行：未完成'), findsOneWidget);
    expect(find.widgetWithText(TextButton, '启用'), findsNothing);
    expect(find.textContaining('重新预约请复制计划'), findsOneWidget);
    await tester.pumpWidget(const SizedBox.shrink());
  });
  testWidgets(
      'startup needs no project login and exposes incomplete background conditions',
      (tester) async {
    await tester.pumpWidget(const XmuApp());
    await tester.pumpAndSettle();
    expect(find.text('所有预约都在这台手机运行'), findsOneWidget);
    expect(find.text('需要完善执行条件'), findsOneWidget);
    expect(find.text('服务器设置'), findsNothing);
    expect(find.text('卡密兑换'), findsNothing);
    expect(calls.every((c) => c.method == 'home'), isTrue);
    await tester.pumpWidget(const SizedBox.shrink());
  });
  testWidgets(
      'school orders explain cached state and do not offer fake cancellation',
      (tester) async {
    await tester.pumpWidget(const XmuApp());
    await tester.pumpAndSettle();
    await tester.tap(find.text('订单').last);
    await tester.pumpAndSettle();
    expect(find.textContaining('以下为本机缓存'), findsOneWidget);
    expect(find.textContaining('取消预约或学校自身'), findsOneWidget);
    expect(find.text('取消预约'), findsNothing);
    await tester.pumpWidget(const SizedBox.shrink());
  });
  testWidgets('new plan is enabled by default and saved through native channel',
      (tester) async {
    await tester.pumpWidget(const XmuApp());
    await tester.pumpAndSettle();
    await tester.tap(find.text('计划').last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('新建本机计划'));
    await tester.pumpAndSettle();
    await tester.enterText(find.widgetWithText(TextFormField, '计划名称'), '本地计划');
    resolvedVenue = {...venuesList.first, 'venue_id': '114', 'project_name': '学校正式项目'};
    await tester.ensureVisible(find.text('查询学校时段与余量'));
    await tester.tap(find.text('查询学校时段与余量'));
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    final weekday = beijingToday().add(const Duration(days: 1)).weekday;
    const weekdays = ['一', '二', '三', '四', '五', '六', '日'];
    final ranges =
        find.widgetWithText(TextFormField, '星期${weekdays[weekday - 1]}候选时段');
    await tester.ensureVisible(ranges);
    await tester.enterText(ranges, '08:00-09:00');
    FocusManager.instance.primaryFocus?.unfocus();
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('我已核对学校当前放号规则'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('我已核对学校当前放号规则'));
    await tester.pumpAndSettle();
    await tester.dragUntilVisible(find.text('保存到本机'),
        find.byType(Scrollable).first, const Offset(0, -300));
    await tester.ensureVisible(find.widgetWithText(FilledButton, '保存到本机'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('保存到本机'));
    await tester.pumpAndSettle();
    final saved = calls.where((c) => c.method == 'savePlan').single;
    final plan = jsonDecode((saved.arguments as Map)['plan'] as String) as Map;
    expect(plan['enabled'], isTrue);
    expect(plan['rule_confirmed'], isTrue);
    expect(plan['venue']['venue_id'], '114');
    expect(plan['venue']['project_name'], '学校正式项目');
    expect(plan['weekdays']['$weekday'], ['08:00-09:00']);
    expect(plan.containsKey('price'), isFalse);
    await tester.pumpWidget(const SizedBox.shrink());
  });
  testWidgets('real execution is automatic without a second master switch',
      (tester) async {
    await tester.pumpWidget(const XmuApp());
    await tester.pumpAndSettle();
    expect(find.byType(SwitchListTile), findsNothing);
    expect(find.text('查看执行条件'), findsOneWidget);
    expect(find.text('后台守护待命'), findsOneWidget);
    expect(calls.where((c) => c.method == 'setExecution'), isEmpty);
    await tester.pumpWidget(const SizedBox.shrink());
  });
  testWidgets(
      'date border belongs to the scrolling date tile during overscroll',
      (tester) async {
    await tester
        .pumpWidget(MaterialApp(home: LocalPlanEditor(venues: venuesList)));
    await tester.pumpAndSettle();
    final date = find.byKey(const ValueKey('entry-date-surface'));
    final tile = find.descendant(of: date, matching: find.byType(ListTile));
    expect(tile, findsOneWidget);
    expect(
        tester.element(
            find.ancestor(of: tile, matching: find.byType(Material)).first),
        tester.element(date));
    expect(tester.widget<Material>(date).clipBehavior, Clip.antiAlias);
    final before = tester.getRect(tile).topLeft - tester.getRect(date).topLeft;
    final gesture = await tester.startGesture(const Offset(150, 180));
    await gesture.moveBy(const Offset(0, 150));
    await tester.pump();
    expect(tester.getRect(tile).topLeft - tester.getRect(date).topLeft, before);
    await gesture.up();
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
  });
  testWidgets('query preserves row closure and global closure flags',
      (tester) async {
    final day = beijingToday()
        .add(const Duration(days: 1))
        .toIso8601String()
        .substring(0, 10);
    var globallyOpen = true;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(LocalApi.channel, (call) async {
      if (call.method != 'availability') return null;
      return jsonEncode({
        'is_can_choose': globallyOpen ? 1 : 0,
        'personal_num': 10,
        'time_slot_list': [
          {
            'date': day,
            'time_range': '09:00-10:00',
            'is_can_choose': 0,
            'slots': [
              {'status': 'available', 'selected': 0}
            ]
          },
          {
            'date': day,
            'time_range': '10:00-11:00',
            'slots': [
              {'status': 'available', 'selected': 0}
            ]
          },
          {
            'date': '2000-01-01',
            'time_range': '11:00-12:00',
            'slots': [
              {'status': 'available', 'selected': 0}
            ]
          },
        ],
      });
    });
    await tester
        .pumpWidget(MaterialApp(home: LocalPlanEditor(venues: venuesList)));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('查询学校时段与余量'));
    await tester.tap(find.text('查询学校时段与余量'));
    await tester.pumpAndSettle();
    var options =
        tester.widgetList<SlotOption>(find.byType(SlotOption)).toList();
    expect(options.length, 2);
    expect(options.first.availability.available, isFalse);
    expect(options.last.availability.available, isTrue);
    globallyOpen = false;
    await tester.ensureVisible(find.text('查询学校时段与余量'));
    await tester.tap(find.text('查询学校时段与余量'));
    await tester.pumpAndSettle();
    options = tester.widgetList<SlotOption>(find.byType(SlotOption)).toList();
    expect(options.every((o) => !o.availability.available), isTrue);
    expect(tester.takeException(), isNull);
  });
  testWidgets(
      'plan editor selects slots, adjusts priority with + and -, and keeps continuous',
      (tester) async {
    final tomorrow = beijingToday().add(const Duration(days: 1));
    final tomorrowStr = tomorrow.toIso8601String().substring(0, 10);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(LocalApi.channel, (call) async {
      calls.add(call);
      if (call.method == 'home') return jsonEncode(home);
      if (call.method == 'savePlan') return 'saved-id';
      if (call.method == 'availability') {
        return jsonEncode({
          'personal_num': 10,
          'time_slot_list': [
            {
              'date': tomorrowStr,
              'time_range': '09:00-10:00',
              'slots': [
                {'status': 'available', 'selected': 2}
              ]
            },
            {
              'date': tomorrowStr,
              'time_range': '10:00-11:00',
              'slots': [
                {'status': 'available', 'selected': 5}
              ]
            }
          ]
        });
      }
      return null;
    });

    await tester.pumpWidget(const XmuApp());
    await tester.pumpAndSettle();
    await tester.tap(find.text('计划').last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('新建本机计划'));
    await tester.pumpAndSettle();

    // Verify multi-venue dropdown shows current venue
    expect(find.text('翔安校区健身房'), findsOneWidget);

    await tester.ensureVisible(find.text('查询学校时段与余量'));
    await tester.tap(find.text('查询学校时段与余量'));
    await tester.pumpAndSettle();

    // Time slot buttons exist and display booked/total without priority prefix
    expect(tester.takeException(), isNull);
    expect(find.text('09:00-10:00 (已预约/总量: 2/10)'), findsOneWidget);
    expect(find.text('10:00-11:00 (已预约/总量: 5/10)'), findsOneWidget);
    expect(find.text('优先级 1'), findsNothing);

    // Select first slot (09:00-10:00) -> gets priority 1 on the right
    await tester.ensureVisible(find.text('10:00-11:00 (已预约/总量: 5/10)'));
    await tester.tap(find.text('09:00-10:00 (已预约/总量: 2/10)'));
    await tester.pumpAndSettle();
    expect(find.text('优先级 1'), findsOneWidget);
    // Button still has just the time range and capacity
    expect(find.text('09:00-10:00 (已预约/总量: 2/10)'), findsOneWidget);

    // Select second slot (10:00-11:00) -> gets priority 2 on the right
    await tester.tap(find.text('10:00-11:00 (已预约/总量: 5/10)'));
    await tester.pumpAndSettle();
    expect(find.text('优先级 1'), findsOneWidget);
    expect(find.text('优先级 2'), findsOneWidget);

    // Tap '+' on the first slot to lower its priority (swap with slot 2)
    final addButtons = find.byIcon(Icons.add);
    expect(addButtons, findsNWidgets(2));
    await tester.ensureVisible(addButtons.first);
    await tester.pumpAndSettle();
    await tester.tap(addButtons.first);
    await tester.pumpAndSettle();

    // Now 10:00-11:00 is priority 1, 09:00-10:00 is priority 2
    expect(find.text('优先级 1'), findsOneWidget);
    expect(find.text('优先级 2'), findsOneWidget);

    // Tap '-' on the second slot (now index 1, which is 09:00-10:00) to swap back
    final removeButtons = find.byIcon(Icons.remove);
    expect(removeButtons, findsNWidgets(2));
    await tester.ensureVisible(removeButtons.last);
    await tester.pumpAndSettle();
    await tester.tap(removeButtons.last);
    await tester.pumpAndSettle();

    // Tap 09:00-10:00 button to cancel selection
    await tester.ensureVisible(find.text('09:00-10:00 (已预约/总量: 2/10)'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('09:00-10:00 (已预约/总量: 2/10)'));
    await tester.pumpAndSettle();

    // Remaining slot (10:00-11:00) automatically re-indexes to priority 1
    expect(find.text('优先级 1'), findsOneWidget);
    expect(find.text('优先级 2'), findsNothing);

    await tester.pumpWidget(const SizedBox.shrink());
  });
  testWidgets('narrow screen and large text keep home accessible',
      (tester) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    tester.platformDispatcher.textScaleFactorTestValue = 2;
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    await tester.pumpWidget(const XmuApp());
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  });
}
