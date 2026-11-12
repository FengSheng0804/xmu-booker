import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:xmu_sports_booking/main.dart';
import 'package:xmu_sports_booking/main_capture.dart';
import 'package:xmu_sports_booking/local/local_api.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final calls = <MethodCall>[];
  final venue = <String, dynamic>{
    'stadium_id': '16',
    'venue_id': '14',
    'category_id': '8',
    'stadium_name': '测试健身房',
    'project_name': '健身房'
  };
  late Json home;
  setUp(() {
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
      'venues': [venue],
      'messages': []
    };
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(LocalApi.channel, (call) async {
      calls.add(call);
      if (call.method == 'home') return jsonEncode(home);
      if (call.method == 'savePlan') return 'saved-id';
      if (call.method == 'availability') {
        return jsonEncode({'time_slot_list': []});
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
  test('timestamps use Beijing regardless of host timezone', () {
    expect(beijingMillis(1794441600000), contains('北京时间'));
    expect(beijingMillis(0), '尚未更新');
  });
  testWidgets(
      'startup needs no project login and exposes incomplete background conditions',
      (tester) async {
    await tester.pumpWidget(const XmuApp());
    await tester.pumpAndSettle();
    expect(find.text('所有预约都在这台手机运行'), findsOneWidget);
    expect(find.textContaining('后台条件不完整'), findsOneWidget);
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
  testWidgets('new plan is paused and saved through native channel',
      (tester) async {
    await tester.pumpWidget(const XmuApp());
    await tester.pumpAndSettle();
    await tester.tap(find.text('计划').last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('新建本机计划'));
    await tester.pumpAndSettle();
    await tester.enterText(find.widgetWithText(TextFormField, '计划名称'), '本地计划');
    final weekday = beijingToday().add(const Duration(days: 1)).weekday;
    const weekdays = ['一', '二', '三', '四', '五', '六', '日'];
    final ranges =
        find.widgetWithText(TextFormField, '星期${weekdays[weekday - 1]}候选时段');
    await tester.ensureVisible(ranges);
    await tester.enterText(ranges, '08:00-09:00');
    FocusManager.instance.primaryFocus?.unfocus();
    await tester.pumpAndSettle();
    await tester.dragUntilVisible(find.text('保存到本机'),
        find.byType(Scrollable).first, const Offset(0, -300));
    await tester.ensureVisible(find.widgetWithText(FilledButton, '保存到本机'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('保存到本机'));
    await tester.pumpAndSettle();
    final saved = calls.where((c) => c.method == 'savePlan').single;
    final plan = jsonDecode((saved.arguments as Map)['plan'] as String) as Map;
    expect(plan['enabled'], isFalse);
    expect(plan['weekdays']['$weekday'], ['08:00-09:00']);
    expect(plan.containsKey('price'), isFalse);
    await tester.pumpWidget(const SizedBox.shrink());
  });
  testWidgets('enabling real submission requires explicit confirmation',
      (tester) async {
    await tester.pumpWidget(const XmuApp());
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.byType(Switch).first);
    await tester.pumpAndSettle();
    await tester.tap(find.byType(Switch).first);
    await tester.pump(const Duration(milliseconds: 300));
    expect(find.text('开启真实预约？'), findsOneWidget);
    await tester.tap(find.text('取消'));
    await tester.pumpAndSettle();
    expect(calls.where((c) => c.method == 'setExecution'), isEmpty);
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
