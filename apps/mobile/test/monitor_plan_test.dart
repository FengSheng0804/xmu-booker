import 'dart:convert';
import 'dart:io';
import 'dart:ui' as ui;
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:xmu_sports_booking/local/local_api.dart';
import 'package:xmu_sports_booking/local/plan_editor.dart';
import 'package:xmu_sports_booking/core/theme.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final venue = <String,dynamic>{'stadium_id':'16','venue_id':'14','category_id':'8',
    'stadium_name':'翔安校区健身房','project_name':'健身房'};
  late List<MethodCall> calls;
  setUp(() {
    calls = [];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(LocalApi.channel, (call) async {
      calls.add(call);
      if (call.method == 'availability') {
        final date = jsonDecode((call.arguments as Map)['target'] as String)['date'];
        return jsonEncode({'is_can_choose':1,'personal_num':10,'time_slot_list':[
          {'date':date,'time_range':'16:30-18:00','slots':[{'status':'available','selected':10}]}]});
      }
      return call.method == 'savePlan' ? 'saved-monitor' : null;
    });
  });
  tearDown(() => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(LocalApi.channel,null));
  Future<void> open(WidgetTester tester, [Json? plan]) async {
    tester.view.physicalSize = const Size(390,844); tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize); addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(MaterialApp(home: Builder(builder:(c) => Scaffold(body: TextButton(
      onPressed:() => Navigator.push(c,MaterialPageRoute(builder:(_) => LocalPlanEditor(venues:[venue],plan:plan))), child:const Text('打开'))))));
    await tester.tap(find.text('打开')); await tester.pumpAndSettle();
  }
  Future<void> reveal(WidgetTester tester, Finder finder) async {
    await tester.scrollUntilVisible(finder,250,scrollable:find.descendant(of:find.byType(ListView),matching:find.byType(Scrollable)).first);
    await Scrollable.ensureVisible(tester.element(finder),alignment:0.5); await tester.pumpAndSettle();
  }
  testWidgets('monitor mode can select a full slot and saves an absolute monitoring window', (tester) async {
    final today=beijingToday(); final now=DateTime.now().millisecondsSinceEpoch;
    final limit=DateTime.utc(today.year,today.month,today.day+1).subtract(const Duration(hours:8)).millisecondsSinceEpoch-1;
    await open(tester,{'name':'','venue':venue,'repeat':'ONCE','date':today.toIso8601String().substring(0,10),'weekdays':{},
      'release_time':'07:00:00','execution_time':'07:00:00','days_before':0,'window_seconds':120,
      'prepare_lead_seconds':0,'priority':1,'rule_confirmed':false,'monitor_start_at':now,
      'monitor_end_at':now+3600000<limit?now+3600000:limit});
    await tester.enterText(find.byType(TextFormField).first,'取消名额监控');
    await tester.tap(find.byKey(const ValueKey('execution-mode'))); await tester.pumpAndSettle();
    await tester.tap(find.text('监控余量').last); await tester.pumpAndSettle();
    await reveal(tester,find.text('查询学校时段与余量')); await tester.tap(find.text('查询学校时段与余量')); await tester.pumpAndSettle();
    final slot = find.byKey(const ValueKey('slot-button-16:30-18:00'));
    await reveal(tester,slot); await tester.tap(slot); await tester.pumpAndSettle();
    expect(find.text('优先级 1'),findsOneWidget);
    await reveal(tester,find.byKey(const ValueKey('monitor-start')));
    expect(find.text('执行时间（北京时间 HH:mm:ss）'),findsNothing);
    await reveal(tester,find.byKey(const ValueKey('monitor-interval')));
    await tester.tap(find.byKey(const ValueKey('monitor-interval'))); await tester.pumpAndSettle();
    await tester.tap(find.text('每 10 秒').last); await tester.pumpAndSettle();
    await reveal(tester,find.text('我确认发现空位后自动预约'));
    await tester.tap(find.text('我确认发现空位后自动预约')); await tester.pumpAndSettle();
    await reveal(tester,find.text('保存到本机')); await tester.tap(find.text('保存到本机')); await tester.pumpAndSettle();
    final saved = jsonDecode((calls.singleWhere((c)=>c.method=='savePlan').arguments as Map)['plan'] as String);
    expect(saved['execution_mode'],'MONITOR'); expect(saved['repeat'],'ONCE'); expect(saved['poll_seconds'],10);
    expect(saved['monitor_end_at'],greaterThan(saved['monitor_start_at']));
    expect((saved['weekdays'] as Map).values.single,['16:30-18:00']);
    expect(saved['enabled'],isTrue); expect(calls.where((c)=>c.method=='runTask'),isEmpty);
    expect(tester.takeException(),isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  });
  testWidgets('invalid monitoring cutoff is rejected before saving', (tester) async {
    final date=beijingToday().add(const Duration(days:1));
    final now=DateTime.now().millisecondsSinceEpoch;
    await open(tester,{'id':'invalid','name':'监控','venue':venue,'repeat':'ONCE','date':date.toIso8601String().substring(0,10),
      'weekdays':{'${date.weekday}':['16:30-18:00']},'execution_mode':'MONITOR', 'monitor_start_at':now+3600000,
      'monitor_end_at':now,'poll_seconds':60,'rule_confirmed':true,'release_time':'07:00:00','execution_time':'07:00:00',
      'days_before':1,'window_seconds':120,'prepare_lead_seconds':0,'priority':1});
    await reveal(tester,find.text('保存到本机')); await tester.tap(find.text('保存到本机')); await tester.pumpAndSettle();
    expect(find.textContaining('监控截止须晚于'),findsOneWidget);
    expect(calls.where((c)=>c.method=='savePlan'),isEmpty);
    await tester.pumpWidget(const SizedBox.shrink());
  });
  test('monitor display preserves unknown submission status', () {
    expect(taskStateText({'state':'UNKNOWN','target':{'execution_mode':'MONITOR'}}),'需要核对，禁止重发');
    expect(taskStateText({'state':'PENDING','run_at':DateTime.now().millisecondsSinceEpoch-1,'target':{'execution_mode':'MONITOR'}}),'监控中');
  });
  for (final layout in [(const Size(375,800),1.0),(const Size(375,800),2.0),(const Size(844,390),1.2)]) {
    testWidgets('monitor controls fit ${layout.$1} at text scale ${layout.$2}',(tester) async {
      tester.view.physicalSize=layout.$1; tester.view.devicePixelRatio=1;
      addTearDown(tester.view.resetPhysicalSize); addTearDown(tester.view.resetDevicePixelRatio);
      final font=Platform.environment['XMU_PREVIEW_FONT'];
      if (font!=null) {
        await (FontLoader('PreviewCJK')..addFont(Future.value(ByteData.sublistView(File(font).readAsBytesSync())))).load();
      }
      await (FontLoader('MaterialIcons')..addFont(rootBundle.load('fonts/MaterialIcons-Regular.otf'))).load();
      final base=buildTheme();
      final family=font==null?null:'PreviewCJK';
      final date=beijingToday().add(const Duration(days:1)); final now=DateTime.now().millisecondsSinceEpoch;
      final p=<String,dynamic>{'name':'取消名额监控','venue':venue,'repeat':'ONCE','date':date.toIso8601String().substring(0,10),
        'weekdays':{'${date.weekday}':['16:30-18:00']},'execution_mode':'MONITOR','monitor_start_at':now,
        'monitor_end_at':now+3600000,'poll_seconds':30,'rule_confirmed':false,'release_time':'07:00:00',
        'execution_time':'07:00:00','days_before':1,'window_seconds':120,'prepare_lead_seconds':0,'priority':1};
      await tester.pumpWidget(MaterialApp(theme:base.copyWith(
        textTheme:base.textTheme.apply(fontFamily:family),primaryTextTheme:base.primaryTextTheme.apply(fontFamily:family),
        appBarTheme:base.appBarTheme.copyWith(titleTextStyle:base.appBarTheme.titleTextStyle?.copyWith(fontFamily:family)),
        listTileTheme:base.listTileTheme.copyWith(titleTextStyle:base.listTileTheme.titleTextStyle?.copyWith(fontFamily:family),
          subtitleTextStyle:base.listTileTheme.subtitleTextStyle?.copyWith(fontFamily:family)),
        filledButtonTheme:FilledButtonThemeData(style:base.filledButtonTheme.style?.copyWith(
          textStyle:WidgetStatePropertyAll(TextStyle(fontFamily:family,fontSize:16))))),
        builder:(context,child)=>MediaQuery(data:MediaQuery.of(context).copyWith(textScaler:TextScaler.linear(layout.$2),disableAnimations:true),child:child!),
        home:RepaintBoundary(key:const ValueKey('monitor-preview'),child:LocalPlanEditor(venues:[venue],plan:p))));
      await tester.pumpAndSettle();
      Future<void> snapshot(String name) async {
        final output=Platform.environment['XMU_MONITOR_PREVIEW'];
        if(output==null || layout.$2!=1.0) return;
        final boundary=tester.renderObject<RenderRepaintBoundary>(find.byKey(const ValueKey('monitor-preview')));
        await tester.runAsync(() async {
          final image=await boundary.toImage(pixelRatio:2); final bytes=await image.toByteData(format:ui.ImageByteFormat.png);
          final file=File('$output/$name.png')..parent.createSync(recursive:true);
          file.writeAsBytesSync(bytes!.buffer.asUint8List()); image.dispose();
        });
      }
      await snapshot('monitor-mode');
      await reveal(tester,find.byKey(const ValueKey('monitor-interval')));
      await tester.pump(const Duration(milliseconds:16));
      await snapshot('monitor-settings');
      expect(tester.takeException(),isNull);
      await reveal(tester,find.text('保存到本机'));
      expect(tester.takeException(),isNull);
      await tester.pumpWidget(const SizedBox.shrink());
    });
  }
}
