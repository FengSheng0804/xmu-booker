import 'dart:convert';
import 'dart:io';
import 'dart:ui' as ui;
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:xmu_sports_booking/local/local_api.dart';
import 'package:xmu_sports_booking/local/local_app.dart';
import 'package:xmu_sports_booking/core/theme.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late Json home;
  late List<MethodCall> calls;
  setUp(() {
    calls = [];
    home = {'authorization': {}, 'permissions': {}, 'plans': [], 'tasks': [],
      'orders': [], 'messages': [], 'venues': []};
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(LocalApi.channel, (call) async {
      calls.add(call);
      if (call.method == 'deleteExecutionRecord') {
        (home['tasks'] as List).removeWhere((row) => row['id'] == (call.arguments as Map)['id']);
      }
      return call.method == 'home' ? jsonEncode(home) : null;
    });
  });
  tearDown(() => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
      .setMockMethodCallHandler(LocalApi.channel, null));

  Future<void> open(WidgetTester tester, {double height = 3000, double width = 600}) async {
    tester.view.physicalSize = Size(width, height);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(const MaterialApp(home: LocalHomePage()));
    await tester.pumpAndSettle();
  }

  testWidgets('orders fill remaining viewport and show ten before scrolling inside the panel', (tester) async {
    home['orders'] = List.generate(25, (i) => {'order_id': '$i', 'stadium_name': '订单 $i', 'detail': []});
    await open(tester, height: 800);
    await tester.tap(find.text('订单').last);
    await tester.pumpAndSettle();
    final list = find.byKey(const ValueKey('school-orders-list'));
    final panel = find.byKey(const ValueKey('school-orders-panel'));
    int count() => tester.widget<ListView>(list).childrenDelegate.estimatedChildCount!;
    final initial = count();
    expect(initial, 11);
    final height = tester.getSize(panel).height;
    expect(height, greaterThan(280));
    expect(tester.getBottomLeft(panel).dy, closeTo(tester.getTopLeft(find.byType(NavigationBar)).dy, 1));
    final outerScroll = tester.state<ScrollableState>(find.descendant(
      of: find.byType(CustomScrollView), matching: find.byType(Scrollable)).first);
    final before = outerScroll.position.pixels;
    await tester.pump(const Duration(seconds: 6));
    expect(count(), initial);
    await tester.drag(list, const Offset(0, -1000)); await tester.pumpAndSettle();
    expect(count(), initial + 10);
    await tester.drag(list, const Offset(0, -1400)); await tester.pumpAndSettle();
    expect(count(), initial + 15);
    await tester.drag(list, const Offset(0, -1400)); await tester.pumpAndSettle();
    expect(count(), initial + 15);
    expect(tester.getSize(panel).height, height);
    expect(outerScroll.position.pixels, before);
    expect(calls.where((c) => c.method == 'syncOrders'), isEmpty);
    tester.view.physicalSize = const Size(600,1000);
    await tester.pumpAndSettle();
    expect(tester.getSize(panel).height, closeTo(height + 200, 1));
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('local messages keep a fixed height and load more inside their own scroll area', (tester) async {
    home['messages'] = List.generate(25, (i) => {'id': '$i', 'text': '测试消息 $i', 'created_at': 1});
    await open(tester);
    final panel = find.byKey(const ValueKey('local-messages-panel'));
    final list = find.byKey(const ValueKey('local-messages-list'));
    expect(tester.getSize(panel).height, 280);
    int count() => tester.widget<ListView>(list).childrenDelegate.estimatedChildCount!;
    expect(count(), 11);
    final outer = find.byType(ListView).first;
    final outerScroll = tester.state<ScrollableState>(find.descendant(of: outer, matching: find.byType(Scrollable)).first);
    final before = outerScroll.position.pixels;
    await tester.drag(list, const Offset(0, -800)); await tester.pumpAndSettle();
    expect(count(), 21);
    await tester.drag(list, const Offset(0, -1200)); await tester.pumpAndSettle();
    expect(count(), 26);
    expect(tester.getSize(panel).height, 280);
    expect(outerScroll.position.pixels, before);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('deleting a finished record requires confirmation and sends its id only', (tester) async {
    home['tasks'] = [{'id': 'finished', 'state': 'FAILED', 'date': '2026-11-13', 'run_at': 1,
      'reason': '测试失败', 'target': {'stadium_name': '测试场馆', 'ranges': []}}];
    await open(tester);
    await tester.tap(find.text('计划').last);
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('删除执行记录'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('取消'));
    await tester.pumpAndSettle();
    expect(calls.where((c) => c.method == 'deleteExecutionRecord'), isEmpty);
    await tester.tap(find.byTooltip('删除执行记录'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, '删除'));
    await tester.pumpAndSettle();
    expect(calls.where((c) => c.method == 'deleteExecutionRecord').single.arguments, {'id': 'finished'});
    expect(find.byTooltip('删除执行记录'), findsNothing);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('compact plan keeps detailed schedules in the sheet', (tester) async {
    await (FontLoader('MaterialIcons')..addFont(rootBundle.load('fonts/MaterialIcons-Regular.otf'))).load();
    home['plans'] = List.generate(3, (i) => {'id': 'p$i', 'name': '游泳预约计划 ${i + 1}',
      'enabled': false, 'repeat': 'WEEKLY', 'execution_time': '07:00:00',
      'venue': {'stadium_name': '翔安校区游泳馆'},
      'weekdays': {'5': ['12:00-14:00', '18:30-21:00']}});
    final font = Platform.environment['XMU_PREVIEW_FONT'];
    if (font != null) {
      await (FontLoader('PreviewCJK')..addFont(Future.value(
          ByteData.sublistView(File(font).readAsBytesSync())))).load();
    }
    await open(tester, height: 800, width: 375);
    final base = buildTheme();
    final family = font == null ? null : 'PreviewCJK';
    await tester.pumpWidget(MaterialApp(theme: base.copyWith(
        textTheme: base.textTheme.apply(fontFamily: family),
        primaryTextTheme: base.primaryTextTheme.apply(fontFamily: family),
        appBarTheme: base.appBarTheme.copyWith(titleTextStyle: base.appBarTheme.titleTextStyle?.copyWith(fontFamily: family)),
        listTileTheme: base.listTileTheme.copyWith(
          titleTextStyle: base.listTileTheme.titleTextStyle?.copyWith(fontFamily: family),
          subtitleTextStyle: base.listTileTheme.subtitleTextStyle?.copyWith(fontFamily: family)),
        filledButtonTheme: FilledButtonThemeData(style: base.filledButtonTheme.style?.copyWith(
          textStyle: WidgetStatePropertyAll(TextStyle(fontFamily: family, fontSize: 16)))),
        navigationBarTheme: base.navigationBarTheme.copyWith(labelTextStyle:
          WidgetStatePropertyAll(TextStyle(fontFamily: family, fontSize: 13)))),
        home: const RepaintBoundary(key: ValueKey('history-preview'), child: LocalHomePage())));
    await tester.pumpAndSettle();
    await tester.tap(find.text('计划').last);
    await tester.pumpAndSettle();
    expect(tester.getSize(find.byKey(const ValueKey('plan-card-p0'))).height, lessThan(150));
    expect(find.textContaining('星期5：'), findsNothing);
    final output = Platform.environment['XMU_HISTORY_PREVIEW'];
    if (output != null) {
      final boundary = tester.renderObject<RenderRepaintBoundary>(find.byKey(const ValueKey('history-preview')));
      await tester.runAsync(() async {
        final image = await boundary.toImage();
        final bytes = await image.toByteData(format: ui.ImageByteFormat.png);
        final file = File(output)..parent.createSync(recursive: true);
        file.writeAsBytesSync(bytes!.buffer.asUint8List()); image.dispose();
      });
    }
    await tester.tap(find.byKey(const ValueKey('plan-card-p0')));
    await tester.pumpAndSettle();
    expect(find.text('星期5：12:00-14:00 → 18:30-21:00'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  });
}
