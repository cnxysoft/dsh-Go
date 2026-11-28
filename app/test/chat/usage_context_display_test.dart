import 'package:dsh_app/state/usage_state.dart';
import 'package:dsh_app/ui/chat/usage_sheet.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/harness.dart';

void main() {
  for (final sheet in [true, false]) {
    testWidgets('context usage keeps values and labels aligned (sheet: $sheet)', (tester) async {
      Future<void> show(SessionUsage usage) async {
        await tester.pumpWidget(
          testApp(
            Scaffold(
              body: SingleChildScrollView(
                child: SessionUsageSections(usage: usage, sheet: sheet),
              ),
            ),
          ),
        );
        await tester.pumpAndSettle();
      }

      await show(
        const SessionUsage(pressureTokens: 840, projectedTokens: 400, contextWindow: 1000, messageTokens: 300),
      );
      expect(find.text('预计下次请求'), findsOneWidget);
      expect(find.text('40%'), findsOneWidget);
      expect(find.text('400 / 1K'), findsOneWidget);
      expect(find.text('累计输入'), findsOneWidget);

      await show(const SessionUsage(pressureTokens: 840, contextWindow: 1000));
      expect(find.text('上次请求'), findsOneWidget);
      expect(find.text('84%'), findsOneWidget);
      expect(find.text('840 / 1K'), findsOneWidget);

      await show(const SessionUsage(contextWindow: 1000));
      expect(find.text('0%'), findsNothing);
      expect(find.text('0 / 1K'), findsNothing);
    });
  }

  testWidgets('billion-scale cumulative input formats as B and never overlaps adjacent columns', (tester) async {
    tester.view.devicePixelRatio = 2;
    tester.view.physicalSize = const Size(360 * 2, 800 * 2);
    addTearDown(tester.view.reset);

    await tester.pumpWidget(
      testApp(
        const Scaffold(
          body: SingleChildScrollView(
            child: SessionUsageSections(
              usage: SessionUsage(
                uncachedInput: 138895600,
                cacheRead: 3333494400,
                output: 8750000,
                turns: 3456,
              ),
              speed: '93',
              sheet: true,
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    final input = find.text('3.47B');
    final output = find.text('8.75M');
    expect(input, findsOneWidget);
    expect(output, findsOneWidget);
    expect(tester.getRect(input).right, lessThanOrEqualTo(tester.getRect(output).left));
    expect(tester.takeException(), isNull);
  });
}
