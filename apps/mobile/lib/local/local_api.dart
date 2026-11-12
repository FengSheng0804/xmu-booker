import 'dart:convert';
import 'package:flutter/services.dart';

typedef Json = Map<String, dynamic>;

/// Native Room is authoritative; this interface has no base URL or HTTP client.
class LocalApi {
  const LocalApi();
  static const channel = MethodChannel('xmu/local_booking');
  Future<T?> call<T>(String method, [Json? args]) =>
      channel.invokeMethod<T>(method, args);
  Future<Json> home() async =>
      jsonDecode((await call<String>('home'))!) as Json;
  Future<List<Json>> syncOrders() async =>
      (jsonDecode((await call<String>('syncOrders'))!) as List).cast<Json>();
  Future<List<Json>> syncCatalog() async =>
      (jsonDecode((await call<String>('syncCatalog'))!) as List).cast<Json>();
  Future<Json> availability(Json target) async => jsonDecode(
          (await call<String>('availability', {'target': jsonEncode(target)}))!)
      as Json;
  Future<String> savePlan(Json plan) async =>
      (await call<String>('savePlan', {'plan': jsonEncode(plan)}))!;
  Future<void> deletePlan(String id) => call('deletePlan', {'id': id});
  Future<void> setExecution(bool value) =>
      call('setExecution', {'enabled': value});
  Future<void> runTask(String id) => call('runTask', {'id': id});
  Future<void> settings(String kind) => call('settings', {'kind': kind});
  Future<void> clear() => call('clear');
}

String localError(Object error) {
  final code = error is PlatformException ? error.code : '';
  return const {
        'EXECUTION_WINDOW_INVALID': '执行时间必须在学校放号后的有效执行窗口内，请检查放号时间和窗口长度',
        'AUTH_REQUIRED': '请先在本机获取或更新学校授权',
        'SCHOOL_REJECTED': '学校未接受请求，请检查授权；没有自动重发预约',
        'SCHOOL_HTTP_FAILED': '学校服务暂未连接，请检查网络后重试',
        'OWNER_MISMATCH': '学校身份不一致，已停止操作，请检查本人授权',
        'NOT_IN_EXECUTION_WINDOW': '尚未到准备时间，任务会在执行窗口内运行',
        'INVALID_PLAN': '计划内容不完整，请检查时段、日期与放号规则',
        'RULE_CONFIRMATION_REQUIRED': '请先核对并确认学校放号规则',
        'INCOMPLETE_ORDER_SCAN': '学校订单未完整读取，请稍后重新同步',
        'MODEL_RUNTIME_UNAVAILABLE': '本机模型未能运行，已停止自动提交',
      }[code] ??
      '操作未完成，请稍后重试${code.isEmpty ? '' : '（$code）'}';
}

String stateText(Object? state) =>
    const {
      'PENDING': '等待执行',
      'CLAIMED': '准备中',
      'SENT': '已提交，待查单',
      'SUCCEEDED': '学校订单已确认',
      'UNKNOWN': '需要核对，禁止重发',
      'FAILED': '未完成',
      'CANCELLED': '已暂停或取消',
      'EXPIRED': '已过执行窗口',
    }[state] ??
    '$state';

String beijingMillis(dynamic value) {
  if (value is! num || value == 0) return '尚未更新';
  final d = DateTime.fromMillisecondsSinceEpoch(value.toInt(), isUtc: true)
      .add(const Duration(hours: 8));
  String two(int n) => n.toString().padLeft(2, '0');
  return '${d.year}-${two(d.month)}-${two(d.day)} ${two(d.hour)}:${two(d.minute)}:${two(d.second)} 北京时间';
}

DateTime beijingToday() {
  final now = DateTime.now().toUtc().add(const Duration(hours: 8));
  return DateTime(now.year, now.month, now.day);
}
