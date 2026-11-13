import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:xmu_sports_booking/local/slot_option.dart';

void main() {
  Map<String, dynamic> row(List<Map<String, dynamic>> slots) => {
        'date': '2026-11-20',
        'personal_num': 10,
        'slots': slots,
      };
  Map<String, dynamic> slot(
          {String status = 'available', int used = 2, int price = 0}) =>
      {'status': status, 'selected': used, 'price': price};

  test('closed, full, paid, and mixed areas reflect actual availability', () {
    expect(
        SlotAvailability.from(row([slot(status: 'locked')])).reason, '未开放预约');
    expect(SlotAvailability.from(row([slot(used: 10)])).reason, '已约满');
    expect(SlotAvailability.from(row([slot(price: 1)])).available, isTrue);
    final mixed = SlotAvailability.from(row([slot(status: 'locked'), slot()]));
    expect(mixed.available, isTrue);
    expect(mixed.booked, 4);
    expect(mixed.total, 20);
    expect(
        SlotAvailability.from({
          ...row([slot()]),
          'is_can_choose': 0
        }).available,
        isFalse);
    expect(
        SlotAvailability.from({
          ...row([slot()]),
          'venue_appointment_min_num': 2
        }).available,
        isFalse);
    expect(
        SlotAvailability.from(row([
          {'status': 'available', 'date': '2026-11-21'}
        ])).available,
        isFalse);
  });

  test('November 13 swimming sessions with school prices remain bookable', () {
    for (final range in ['12:00-14:00', '18:30-21:00']) {
      final result = SlotAvailability.from({
        ...row([slot(price: 20)]),
        'date': '2026-11-13',
        'time_range': range,
        'is_can_choose': 1,
      });
      expect(result.available, isTrue);
      expect(result.reason, isNull);
    }
  });

  for (final size in [const Size(375, 844), const Size(844, 375)]) {
    for (final scale in [1.0, 2.0]) {
      testWidgets('selection keeps slot bounds at $size text $scale',
          (tester) async {
        tester.view.physicalSize = size;
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);
        var selected = false;
        await tester.pumpWidget(MaterialApp(
            home: Scaffold(
                body: MediaQuery(
          data: MediaQueryData(
              size: size,
              textScaler: TextScaler.linear(scale),
              disableAnimations: true),
          child: StatefulBuilder(
              builder: (context, setState) => Padding(
                    padding: const EdgeInsets.all(20),
                    child: SlotOption(
                        range: '09:00-10:00',
                        availability: const SlotAvailability(2, 10, null),
                        priority: selected ? 1 : null,
                        busy: false,
                        onToggle: () => setState(() => selected = !selected)),
                  )),
        ))));
        final button = find.byKey(const ValueKey('slot-button-09:00-10:00'));
        final rowFinder = find.byKey(const ValueKey('slot-row-09:00-10:00'));
        final before = tester.getRect(button);
        final rowBefore = tester.getRect(rowFinder);
        expect(rowBefore.width, size.width - 40);
        expect(rowBefore.left, before.left);
        expect(rowBefore.right - before.right, 96);
        // The blank priority area is inside the same row, but does not select it.
        await tester.tapAt(Offset(rowBefore.right - 20, rowBefore.center.dy));
        await tester.pumpAndSettle();
        expect(selected, isFalse);
        await tester.tap(button);
        await tester.pumpAndSettle();
        expect(tester.getRect(button), before);
        expect(tester.getRect(rowFinder), rowBefore);
        expect(find.text('优先级 1'), findsOneWidget);
        expect(
            tester.getRect(find.text('优先级 1')).left, greaterThan(before.right));
        expect(tester.getRect(find.text('优先级 1')).right,
            lessThan(rowBefore.right));
        expect(tester.takeException(), isNull);
        await tester.tap(button);
        await tester.pumpAndSettle();
        expect(tester.getRect(button), before);
      });
    }
  }

  for (final reason in ['未开放预约', '已约满']) {
    testWidgets('future candidate can be selected and deselected: $reason',
        (tester) async {
      var selected = false;
      var busy = false;
      late StateSetter update;
      await tester.pumpWidget(MaterialApp(
          home: Scaffold(body: StatefulBuilder(builder: (context, setState) {
        update = setState;
        return SlotOption(
            range: '09:00-10:00',
            availability: SlotAvailability(0, 10, reason),
            allowPlanning: true,
            priority: selected ? 1 : null,
            busy: busy,
            onToggle: () => setState(() => selected = !selected));
      }))));
      final button = find.byKey(const ValueKey('slot-button-09:00-10:00'));
      expect(find.text('09:00-10:00（可预设，当前$reason）'), findsOneWidget);
      await tester.tap(button);
      await tester.pumpAndSettle();
      expect(selected, isTrue);
      expect(tester.widget<Material>(button).color, Colors.green.shade600);
      expect(find.text('优先级 1'), findsOneWidget);
      update(() => busy = true);
      await tester.pump();
      await tester.tap(button);
      expect(selected, isTrue);
      update(() => busy = false);
      await tester.pump();
      await tester.tap(button);
      await tester.pumpAndSettle();
      expect(selected, isFalse);
    });
  }

  testWidgets('unavailable slot is grey, explains why, and cannot be selected',
      (tester) async {
    var taps = 0;
    await tester.pumpWidget(MaterialApp(
        home: Scaffold(
            body: SlotOption(
                range: '09:00-10:00',
                availability: const SlotAvailability(0, 10, '未开放预约'),
                priority: null,
                busy: false,
                onToggle: () => taps++))));
    final button = find.byKey(const ValueKey('slot-button-09:00-10:00'));
    expect(tester.widget<Material>(button).color, Colors.grey.shade200);
    expect(find.textContaining('不可预约：未开放预约'), findsOneWidget);
    expect(find.textContaining('已预约/总量'), findsNothing);
    await tester.tap(button);
    await tester.pumpAndSettle();
    expect(taps, 0);
  });
}
