import 'package:flutter/material.dart';

abstract final class AppColors {
  static const navy = Color(0xFF142F48);
  static const teal = Color(0xFF087E83);
  static const statusTeal = Color(0xFF06666B);
  static const canvas = Color(0xFFF5F7FA);
  static const muted = Color(0xFF526477);
  static const border = Color(0xFFE0E7ED);
  static const controlBorder = Color(0xFF7A8E9E);
  static const warning = Color(0xFF805200);
  static const warningSurface = Color(0xFFFFF3D6);
  static const danger = Color(0xFFB3261E);
  static const dangerSurface = Color(0xFFFCEBE9);
  static const success = Color(0xFF216B48);
}

ThemeData buildTheme() {
  final scheme = ColorScheme.fromSeed(
      seedColor: AppColors.teal,
      primary: AppColors.teal,
      secondary: AppColors.navy,
      surface: Colors.white,
      error: AppColors.danger);
  final base = ThemeData(useMaterial3: true, colorScheme: scheme);
  final shape = RoundedRectangleBorder(borderRadius: BorderRadius.circular(16));
  return base.copyWith(
    scaffoldBackgroundColor: AppColors.canvas,
    textTheme: base.textTheme
        .apply(bodyColor: AppColors.navy, displayColor: AppColors.navy)
        .copyWith(
          titleMedium: const TextStyle(
              fontSize: 16,
              height: 1.35,
              fontWeight: FontWeight.w600,
              color: AppColors.navy),
          bodyLarge:
              const TextStyle(fontSize: 16, height: 1.4, color: AppColors.navy),
          bodyMedium: const TextStyle(
              fontSize: 14, height: 1.45, color: AppColors.navy),
          bodySmall: const TextStyle(
              fontSize: 12, height: 1.4, color: AppColors.muted),
        ),
    appBarTheme: AppBarTheme(
        centerTitle: false,
        toolbarHeight: 52,
        backgroundColor: AppColors.canvas,
        foregroundColor: AppColors.navy,
        surfaceTintColor: Colors.transparent,
        titleTextStyle: base.textTheme.titleLarge?.copyWith(
            fontSize: 20, fontWeight: FontWeight.w700, color: AppColors.navy)),
    cardTheme: CardThemeData(
        elevation: 0,
        color: Colors.white,
        margin: const EdgeInsets.symmetric(vertical: 4),
        shape: shape.copyWith(side: const BorderSide(color: AppColors.border)),
        clipBehavior: Clip.antiAlias),
    inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: Colors.white,
        contentPadding:
            const EdgeInsets.symmetric(horizontal: 14, vertical: 14),
        labelStyle: const TextStyle(color: AppColors.muted),
        border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
        enabledBorder: OutlineInputBorder(
            borderRadius: BorderRadius.circular(12),
            borderSide: const BorderSide(color: AppColors.controlBorder)),
        focusedBorder: OutlineInputBorder(
            borderRadius: BorderRadius.circular(12),
            borderSide: const BorderSide(color: AppColors.teal, width: 2))),
    filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
            minimumSize: const Size(48, 48),
            shape:
                RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
            textStyle: base.textTheme.labelLarge
                ?.copyWith(fontSize: 16, fontWeight: FontWeight.w600))),
    outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
            minimumSize: const Size(48, 48),
            shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(12)))),
    textButtonTheme: TextButtonThemeData(
        style: TextButton.styleFrom(minimumSize: const Size(48, 48))),
    iconButtonTheme: IconButtonThemeData(
        style: IconButton.styleFrom(minimumSize: const Size(48, 48))),
    listTileTheme: const ListTileThemeData(
        contentPadding: EdgeInsets.symmetric(horizontal: 14, vertical: 4),
        minVerticalPadding: 8,
        minTileHeight: 56,
        iconColor: AppColors.teal,
        textColor: AppColors.navy,
        titleTextStyle: TextStyle(
            color: AppColors.navy,
            fontSize: 15,
            height: 1.4,
            fontWeight: FontWeight.w500),
        subtitleTextStyle:
            TextStyle(color: AppColors.muted, fontSize: 13, height: 1.45)),
    navigationBarTheme: NavigationBarThemeData(
        backgroundColor: Colors.white,
        surfaceTintColor: Colors.transparent,
        indicatorColor: const Color(0xFFD8EFEC),
        elevation: 0,
        height: 68,
        labelTextStyle: WidgetStateProperty.all(base.textTheme.labelMedium
            ?.copyWith(fontSize: 13, fontWeight: FontWeight.w600))),
    dividerTheme: const DividerThemeData(color: AppColors.border, space: 16),
    dialogTheme: DialogThemeData(backgroundColor: Colors.white, shape: shape),
    snackBarTheme: SnackBarThemeData(
        behavior: SnackBarBehavior.floating,
        backgroundColor: AppColors.navy,
        shape: shape),
  );
}
