import 'dart:convert';
import 'dart:io';
import 'dart:ui' as ui;
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:qr_flutter/qr_flutter.dart';
import 'package:xmu_sports_booking/local/local_api.dart';
import 'package:xmu_sports_booking/local/local_app.dart';
import 'package:xmu_sports_booking/local/plan_editor.dart';
import 'package:xmu_sports_booking/local/entry_pass.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final venue = <String, dynamic>{'stadium_id': '12', 'venue_id': '10', 'category_id': '6',
    'stadium_name': '翔安校区游泳馆', 'project_name': '游泳馆'};
  late List<MethodCall> calls;
  setUp(() {
    calls = [];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(LocalApi.channel, (call) async {
      calls.add(call);
      if (call.method == 'home') {
        return jsonEncode({'plans': [], 'orders': [{'order_id': 'owned', ...venue, 'detail': []}], 'messages': [], 'venues': [venue],
          'tasks': [{'id': 'successful', 'state': 'SUCCEEDED', 'date': '2026-11-13', 'run_at': 1,
            'deadline': 2, 'target': {...venue, 'ranges': ['18:30-21:00']}}]});
      }
      if (call.method == 'orderEntryPass') {
        return jsonEncode({'passes': [{'date': '2026-11-13', 'range': '18:30-21:00',
          'area_name': '泳池', 'payload': '12345|synthetic-code'}]});
      }
      if (call.method == 'savePlan') return 'new-execution-revision';
      return null;
    });
  });
  tearDown(() => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
      .setMockMethodCallHandler(LocalApi.channel, null));

  for (final size in [const Size(375, 800), const Size(844, 390)]) {
    testWidgets('fullscreen entry code fits $size with large text and reduced motion', (tester) async {
      tester.view.physicalSize = size; tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize); addTearDown(tester.view.resetDevicePixelRatio);
      final font = Platform.environment['XMU_PREVIEW_FONT'];
      if (font != null) {
        await (FontLoader('PreviewCJK')..addFont(Future.value(ByteData.sublistView(File(font).readAsBytesSync())))).load();
        await (FontLoader('MaterialIcons')..addFont(rootBundle.load('fonts/MaterialIcons-Regular.otf'))).load();
      }
      await tester.pumpWidget(MaterialApp(theme: ThemeData(fontFamily: font == null ? null : 'PreviewCJK'),
        builder: (context, child) => RepaintBoundary(key: const ValueKey('fullscreen-preview'), child: MediaQuery(
        data: MediaQuery.of(context).copyWith(textScaler: const TextScaler.linear(2), disableAnimations: true), child: child!)),
        home: const Scaffold(body: SingleChildScrollView(child: EntryPassView(orderId: 'owned')))));
      await tester.pumpAndSettle();
      await tester.tap(find.byType(QrImageView)); await tester.pumpAndSettle();
      final qr = find.byKey(const ValueKey('fullscreen-entry-qr'));
      expect(qr, findsOneWidget);
      expect(find.text('入场二维码'), findsNothing);
      expect(tester.getSize(qr).width, lessThanOrEqualTo(size.width - 32));
      expect(tester.getSize(qr).height, lessThan(size.height));
      expect(tester.takeException(), isNull);
      final preview = Platform.environment['XMU_R67_PREVIEW_DIR'];
      if (preview != null) {
        await tester.pump(const Duration(milliseconds: 16));
        final boundary = tester.renderObject<RenderRepaintBoundary>(find.byKey(const ValueKey('fullscreen-preview')));
        await tester.runAsync(() async {
          final image = await boundary.toImage(pixelRatio: 1);
          final bytes = await image.toByteData(format: ui.ImageByteFormat.png);
          await File('$preview/qr-${size.width.toInt()}.png').writeAsBytes(bytes!.buffer.asUint8List());
          image.dispose();
        });
      }
      await tester.tap(find.byType(CloseButton)); await tester.pumpAndSettle();
      expect(qr, findsNothing);
      expect(calls.where((c) => c.method == 'orderEntryPass').length, 1);
      await tester.pumpWidget(const SizedBox.shrink());
    });
  }

  testWidgets('entry code is fetched only from an opened school order, never a task', (tester) async {
    await tester.pumpWidget(const MaterialApp(home: LocalHomePage()));
    await tester.pumpAndSettle();
    await tester.tap(find.text('计划').last);
    await tester.pumpAndSettle();
    expect(calls.where((c) => c.method == 'entryPass'), isEmpty);
    expect(find.byType(QrImageView), findsNothing);
    await tester.tap(find.byKey(const ValueKey('execution-record-successful')));
    await tester.pumpAndSettle();
    expect(calls.where((c) => c.method == 'entryPass' || c.method == 'orderEntryPass'), isEmpty);
    expect(find.byType(QrImageView), findsNothing);
    await tester.tap(find.text('前往学校订单查看入场码'));
    await tester.pumpAndSettle();
    expect(calls.where((c) => c.method == 'orderEntryPass'), isEmpty);
    await tester.tap(find.byKey(const ValueKey('order-owned')));
    await tester.pumpAndSettle();
    expect(calls.where((c) => c.method == 'orderEntryPass').single.arguments, {'id': 'owned'});
    expect(find.text('入场二维码'), findsOneWidget);
    expect(find.byType(QrImageView), findsOneWidget);
    await tester.tap(find.byType(QrImageView));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('fullscreen-entry-qr')), findsOneWidget);
    expect(calls.where((c) => c.method == 'orderEntryPass').length, 1);
    await tester.tap(find.byType(CloseButton));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('fullscreen-entry-qr')), findsNothing);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  Json endedPlan() {
    final day = beijingToday().add(const Duration(days: 2));
    return {'id': 'ended', 'name': '已结束计划', 'venue': venue, 'repeat': 'ONCE',
      'date': day.toIso8601String().substring(0, 10), 'weekdays': {'${day.weekday}': ['18:30-21:00']},
      'execution_time': '07:00:00', 'release_time': '07:00:00', 'days_before': 1,
      'window_seconds': 120, 'prepare_lead_seconds': 15, 'priority': 1,
      'rule_confirmed': true, 'enabled': false, 'last_result': {'state': 'SUCCEEDED', 'date': '2026-11-13'}};
  }

  Future<void> openEditor(WidgetTester tester, Json plan) async {
    await tester.pumpWidget(MaterialApp(home: Builder(builder: (context) => Scaffold(body:
      TextButton(onPressed: () => Navigator.push(context, MaterialPageRoute(builder: (_) =>
          LocalPlanEditor(venues: [venue], plan: plan))), child: const Text('打开编辑'))))));
    await tester.tap(find.text('打开编辑'));
    await tester.pumpAndSettle();
  }

  testWidgets('cancelling an ended-plan edit preserves the original result', (tester) async {
    final original = endedPlan();
    await openEditor(tester, original);
    expect(find.text('启用本机计划'), findsNothing);
    await tester.pageBack();
    await tester.pumpAndSettle();
    expect(calls.where((c) => c.method == 'savePlan'), isEmpty);
    expect(original['last_result']['state'], 'SUCCEEDED');
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('saving an ended-plan edit requests fresh execution and defaults to enabled', (tester) async {
    await openEditor(tester, endedPlan());
    await tester.dragUntilVisible(find.text('保存到本机'), find.byType(Scrollable).first, const Offset(0, -350));
    await Scrollable.ensureVisible(tester.element(find.text('保存到本机')), alignment: 0.5);
    await tester.pumpAndSettle();
    await tester.tap(find.text('保存到本机'));
    await tester.pumpAndSettle();
    final saved = jsonDecode((calls.where((c) => c.method == 'savePlan').single.arguments as Map)['plan'] as String);
    expect(saved['reset_execution'], isTrue);
    expect(saved['enabled'], isTrue);
    expect(saved.containsKey('last_result'), isFalse);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('synthetic entry QR renders as a scanner-readable artifact', (tester) async {
    const key = ValueKey('synthetic-entry-qr');
    await tester.pumpWidget(MaterialApp(home: Center(child: RepaintBoundary(key: key,
      child: QrImageView(data: '12345|synthetic-code', size: 280, padding: const EdgeInsets.all(48),
        backgroundColor: Colors.white, errorCorrectionLevel: QrErrorCorrectLevel.H)))));
    await tester.pumpAndSettle();
    final output = Platform.environment['XMU_SYNTHETIC_QR_PREVIEW'];
    if (output != null) {
      final boundary = tester.renderObject<RenderRepaintBoundary>(find.byKey(key));
      await tester.runAsync(() async {
        final image = await boundary.toImage(pixelRatio: 2);
        final bytes = await image.toByteData(format: ui.ImageByteFormat.png);
        final file = File(output)..parent.createSync(recursive: true);
        file.writeAsBytesSync(bytes!.buffer.asUint8List()); image.dispose();
      });
    }
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  });
}
