import 'package:flutter/material.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'core/theme.dart';
import 'local/local_app.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  LicenseRegistry.addLicense(() async* {
    for (final name in [
      'NOTICE.txt',
      'Apache-2.0.txt',
      'LGPL-3.0.txt',
      'GPL-3.0.txt',
      'Conscrypt-LICENSE.txt',
      'LibADB-COPYING.txt',
      'ONNX-Runtime-LICENSE.txt',
      'BouncyCastle-LICENSE.html',
    ]) {
      yield LicenseEntryWithLineBreaks(['Android native dependencies'],
          await rootBundle.loadString('assets/third_party/$name'));
    }
  });
  runApp(const XmuApp());
}

class XmuApp extends StatelessWidget {
  const XmuApp({super.key});
  @override
  Widget build(BuildContext context) => MaterialApp(
        title: '厦大体育预约',
        theme: buildTheme(),
        home: const LocalHomePage(),
        locale: const Locale('zh', 'CN'),
        supportedLocales: const [Locale('zh', 'CN')],
        localizationsDelegates: const [
          GlobalMaterialLocalizations.delegate,
          GlobalWidgetsLocalizations.delegate,
          GlobalCupertinoLocalizations.delegate
        ],
        debugShowCheckedModeBanner: false,
      );
}
