import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:xmu_sports_booking/main_capture.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('xmu/own_sports_capture');
  late Map<String, dynamic> status;
  late List<MethodCall> calls;
  String? failure;
  setUp(() {
    status = {
      'has_token': true,
      'owner': 'synthetic-owner',
      'verified_at': 1,
      'orders_count': 0,
      'adb_paired': true,
      'adb_connected': false,
      'connection_port': 46295,
    };
    calls = [];
    failure = null;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      if (call.method == 'capture') {
        if (failure != null) throw PlatformException(code: failure!);
        return {...status, 'connection_port': 38721};
      }
      return status;
    });
  });
  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  Future<void> startUpdate(WidgetTester tester) async {
    await tester.pumpWidget(const MaterialApp(home: OwnSportsCapturePage()));
    await tester.pumpAndSettle();
    await tester.tap(find.text('更新授权'));
    await tester.pumpAndSettle();
    await tester.scrollUntilVisible(find.byKey(const ValueKey('capture-student')), 350,
        scrollable: find.byType(Scrollable).first);
    await tester.enterText(find.byKey(const ValueKey('capture-student')), '12345678');
    await tester.ensureVisible(find.text('开始微信授权'));
    await tester.tap(find.text('开始微信授权'));
    await tester.pumpAndSettle();
  }

  testWidgets('closed port gives recovery steps while keeping existing authorization', (tester) async {
    failure = 'ADB_CONNECTION_PORT_CLOSED';
    await startUpdate(tester);
    expect(calls.where((c) => c.method == 'capture').length, 1);
    expect(find.textContaining('连接端口已关闭或已变化'), findsOneWidget);
    expect(find.textContaining('不需要重复配对'), findsOneWidget);
    await tester.scrollUntilVisible(find.text('授权已在本机加密保存'), -350,
        scrollable: find.byType(Scrollable).first);
    expect(find.text('授权已在本机加密保存'), findsOneWidget);
    expect(calls.where((c) => c.method == 'forget' || c.method == 'pair'), isEmpty);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('recovered port is reflected after successful update', (tester) async {
    await startUpdate(tester);
    expect(find.textContaining('已取得本人授权并加密保存'), findsOneWidget);
    await tester.scrollUntilVisible(find.text('更新授权'), -350,
        scrollable: find.byType(Scrollable).first);
    await tester.tap(find.text('更新授权'));
    await tester.pumpAndSettle();
    await tester.scrollUntilVisible(find.byKey(const ValueKey('capture-port')), 350,
        scrollable: find.byType(Scrollable).first);
    final field = tester.widget<TextFormField>(find.byKey(const ValueKey('capture-port')));
    expect(field.controller!.text, '38721');
    await tester.pumpWidget(const SizedBox.shrink());
  });
}
