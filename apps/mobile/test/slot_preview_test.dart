import 'dart:io';
import 'dart:ui' as ui;
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:xmu_sports_booking/local/slot_option.dart';

void main() {
  testWidgets(
      'one rounded row contains separate selection and priority regions',
      (tester) async {
    tester.view.physicalSize = const Size(375, 500);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await (FontLoader('MaterialIcons')
          ..addFont(rootBundle.load('fonts/MaterialIcons-Regular.otf')))
        .load();
    final fontPath = Platform.environment['XMU_PREVIEW_FONT'];
    if (fontPath != null) {
      final loader = FontLoader('PreviewCJK')
        ..addFont(Future.value(
            ByteData.sublistView(File(fontPath).readAsBytesSync())));
      await loader.load();
    }
    var toggles = 0, priorityChanges = 0;
    const boundaryKey = ValueKey('slot-preview');
    await tester.pumpWidget(MaterialApp(
      theme: ThemeData(fontFamily: fontPath == null ? null : 'PreviewCJK'),
      home: Scaffold(
          body: RepaintBoundary(
              key: boundaryKey,
              child: Container(
                color: const Color(0xFFF7F7FC),
                padding: const EdgeInsets.all(20),
                child: Column(mainAxisSize: MainAxisSize.min, children: [
                  SlotOption(
                      range: '12:00-14:00',
                      availability: const SlotAvailability(85, 95, null),
                      priority: 1,
                      busy: false,
                      onToggle: () => toggles++,
                      onLater: () => priorityChanges++),
                  SlotOption(
                      range: '18:30-21:00',
                      availability: const SlotAvailability(5, 95, null),
                      priority: null,
                      busy: false,
                      onToggle: () => toggles++),
                  SlotOption(
                      range: '08:00-10:00',
                      availability: const SlotAvailability(0, 95, '未开放预约'),
                      priority: null,
                      busy: false,
                      onToggle: () => toggles++),
                ]),
              ))),
    ));
    await tester.pumpAndSettle();
    // Ahem, Flutter's placeholder test font, has different metrics from device fonts.
    if (fontPath != null) {
      final label = find.text('12:00-14:00 (已预约/总量: 85/95)');
      final labelWidget = tester.widget<Text>(label);
      final paragraph = tester.renderObject<RenderParagraph>(label);
      final painter = TextPainter(
        text: TextSpan(text: labelWidget.data, style: paragraph.text.style),
        textDirection: TextDirection.ltr,
      )..layout(maxWidth: paragraph.size.width);
      expect(painter.computeLineMetrics().length, 1);
      painter.dispose();
      final priorityParagraph =
          tester.renderObject<RenderParagraph>(find.text('优先级 1'));
      final priorityPainter = TextPainter(
        text: priorityParagraph.text,
        textDirection: TextDirection.ltr,
      )..layout(maxWidth: priorityParagraph.size.width);
      expect(priorityPainter.computeLineMetrics().length, 1);
      priorityPainter.dispose();
      final unavailableParagraph = tester
          .renderObject<RenderParagraph>(find.text('08:00-10:00（不可预约：未开放预约）'));
      final unavailablePainter = TextPainter(
        text: unavailableParagraph.text,
        textDirection: TextDirection.ltr,
      )..layout(maxWidth: unavailableParagraph.size.width);
      expect(unavailablePainter.computeLineMetrics().length, 1);
      unavailablePainter.dispose();
    }
    await tester.tap(find.byIcon(Icons.add).first);
    await tester.pumpAndSettle();
    expect(priorityChanges, 1);
    expect(toggles, 0);
    expect(tester.takeException(), isNull);
    final outputPath = Platform.environment['XMU_PREVIEW_OUTPUT'];
    if (outputPath != null) {
      final boundary =
          tester.renderObject<RenderRepaintBoundary>(find.byKey(boundaryKey));
      await tester.runAsync(() async {
        final preview = await boundary.toImage(pixelRatio: 2);
        final data = await preview.toByteData(format: ui.ImageByteFormat.png);
        final file = File(outputPath);
        file.parent.createSync(recursive: true);
        file.writeAsBytesSync(data!.buffer.asUint8List());
        preview.dispose();
      });
    }
  });
}
