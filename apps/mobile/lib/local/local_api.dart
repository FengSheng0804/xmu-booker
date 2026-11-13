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
  Future<void> deleteExecutionRecord(String id) => call('deleteExecutionRecord', {'id': id});
  Future<Json> entryPass(String id) async => jsonDecode((await call<String>('entryPass', {'id': id}))!) as Json;
  Future<Json> orderEntryPass(String id) async => jsonDecode((await call<String>('orderEntryPass', {'id': id}))!) as Json;
  Future<void> setExecution(bool value) =>
      call('setExecution', {'enabled': value});
  Future<void> runTask(String id) => call('runTask', {'id': id});
  Future<Json> inspectTask(String id) async =>
      jsonDecode((await call<String>('inspectTask', {'id': id}))!) as Json;
  Future<Json> releaseTask(String id) async => jsonDecode((await call<String>(
      'releaseTask', {'id': id, 'confirmed_no_order': true}))!) as Json;
  Future<void> settings(String kind) => call('settings', {'kind': kind});
  Future<void> clear() => call('clear');
  Future<int> cleanStorage() async => (await call<int>('cleanStorage')) ?? 0;
}

String localError(Object error) {
  final code = error is PlatformException ? error.code : '';
  if (error is PlatformException &&
      code == 'SCHOOL_REJECTED' &&
      (error.message ?? '').startsWith('学校拒绝请求（步骤：')) {
    return error.message!;
  }
  if (error is PlatformException &&
      const ['READ_CHECK_IDENTITY_REJECTED', 'READ_CHECK_LOGIN_REJECTED',
        'READ_CHECK_ORDERS_REJECTED'].contains(code) &&
      (error.message ?? '').startsWith('学校授权校验未通过（步骤：')) {
    return error.message!;
  }
  return const {
        'EXECUTION_WINDOW_INVALID': '执行窗口须为15至3600秒，从计划执行时间开始计算',
        'MONITOR_WINDOW_INVALID': '监控起止时间须有效，截止晚于当前时间，最长72小时且不超过入场日期',
        'MONITOR_INTERVAL_INVALID': '监控检查间隔须为10至600秒',
        'AUTH_REQUIRED': '请先在本机获取或更新学校授权',
        'ENTRY_PASS_UNAVAILABLE': '学校当前未提供可用入场二维码，请核对订单是否有效或已核销',
        'READ_CHECK_REJECTED': '学校未接受已保存的授权，请在微信重新进入体育预约页面，再到本机设置更新学校授权后查单；待核对记录会保留',
        'READ_CHECK_IDENTITY_REJECTED': '学校本人身份校验未通过，请更新学校授权后查单',
        'READ_CHECK_LOGIN_REJECTED': '学校未能建立体育会话，请更新学校授权后查单；待核对记录会保留',
        'READ_CHECK_ORDERS_REJECTED': '学校未接受本人订单读取，请更新学校授权后重试；待核对记录会保留',
        'READ_CHECK_HTTP_FAILED': '学校授权服务暂未连接，请检查网络后重试',
        'SCHOOL_REJECTED': '学校未接受请求，请检查授权；没有自动重发预约',
        'SCHOOL_HTTP_FAILED': '学校服务暂未连接，请检查网络后重试',
        'VENUE_MAPPING_CHANGED': '场馆项目对应关系已变化或有多个场地，请先同步学校场馆并重新选择后查询',
        'VENUE_MISMATCH': '学校返回的场馆与当前选择不一致，请同步学校场馆后重新选择',
        'OWNER_MISMATCH': '学校身份不一致，已停止操作，请检查本人授权',
        'NOT_IN_EXECUTION_WINDOW': '尚未到准备时间，任务会在执行窗口内运行',
        'INVALID_PLAN': '计划内容不完整，请检查时段、日期与放号规则',
        'SINGLE_PLAN_FINISHED': '该单次计划已结束，请复制计划或修改入场日期后再启用',
        'RULE_CONFIRMATION_REQUIRED': '请先核对并确认学校放号规则',
        'INCOMPLETE_ORDER_SCAN': '学校订单未完整读取，请稍后重新同步',
        'MODEL_RUNTIME_UNAVAILABLE': '本机模型未能运行，已停止自动提交',
        'TASK_IN_PROGRESS': '该任务正在执行或状态已改变，请刷新后再处理',
        'RECONCILIATION_CHECK_REQUIRED': '请先完整查单，确认核对结果后再处理旧任务',
        'RECONCILIATION_CONFIRMATION_REQUIRED': '解除阻塞前请确认学校原入口没有对应订单',
      }[code] ??
      '操作未完成，请稍后重试${code.isEmpty ? '' : '（$code）'}';
}

bool singlePlanEnded(Json plan) {
  final result = plan['last_result'];
  return plan['repeat'] == 'ONCE' &&
      result is Map &&
      result['date'] == plan['date'] &&
      ['SUCCEEDED', 'FAILED', 'EXPIRED', 'UNKNOWN', 'PAYMENT_REQUIRED']
          .contains(result['state']);
}

String planStatus(Json plan) => singlePlanEnded(plan)
    ? '已结束（${stateText((plan['last_result'] as Map)['state'])}）'
    : plan['enabled'] == true
        ? plan['execution_mode'] == 'MONITOR'
          ? ((plan['monitor_start_at'] as num?) ?? 0) <= DateTime.now().millisecondsSinceEpoch ? '监控中' : '等待监控'
          : '已启用'
        : '已暂停';

String executionReason(Object? reason) => reason == '预约未完成：SCHOOL_REJECTED'
    ? '预约未完成：学校拒绝请求（旧记录未保存具体步骤和原因）'
    : '$reason'.contains('PENDING_RECONCILIATION')
        ? '预约未执行：同场馆同日有旧任务待核对，请打开详情处理旧任务阻塞'
        : '${reason ?? ''}';

String taskStateText(Json task) => task['target']?['execution_mode'] == 'MONITOR' && task['state'] == 'PENDING'
    ? ((task['run_at'] as num?) ?? 0) <= DateTime.now().millisecondsSinceEpoch ? '监控中' : '等待监控'
    : stateText(task['state']);

String stateText(Object? state) =>
    const {
      'PENDING': '等待执行',
      'CLAIMED': '准备中',
      'SENT': '已提交，待查单',
      'SUCCEEDED': '预约成功（学校订单已确认）',
      'UNKNOWN': '需要核对，禁止重发',
      'PAYMENT_REQUIRED': '学校订单待支付',
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
