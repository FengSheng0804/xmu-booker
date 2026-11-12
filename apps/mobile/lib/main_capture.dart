import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// Only the port is used: native transport always connects to this phone's loopback address.
int? localDebugPort(String input) {
  var value = input.trim();
  if (value.contains(':')) {
    final pieces = value.split(':');
    if (pieces.length != 2) return null;
    final octets = pieces.first.split('.');
    if (octets.length != 4 ||
        octets.any((part) =>
            int.tryParse(part) == null ||
            int.parse(part) < 0 ||
            int.parse(part) > 255)) {
      return null;
    }
    value = pieces.last;
  }
  if (!RegExp(r'^[0-9]{1,5}$').hasMatch(value)) return null;
  final port = int.parse(value);
  return port >= 1024 && port <= 65535 ? port : null;
}

class OwnSportsCapturePage extends StatefulWidget {
  const OwnSportsCapturePage({super.key});
  @override
  State<OwnSportsCapturePage> createState() => _OwnSportsCapturePageState();
}

class _OwnSportsCapturePageState extends State<OwnSportsCapturePage>
    with WidgetsBindingObserver {
  static const channel = MethodChannel('xmu/own_sports_capture');
  final student = TextEditingController();
  final connectionPort = TextEditingController();
  final scroll = ScrollController();
  Map<String, dynamic> status = {};
  bool busy = false;
  bool loading = true;
  bool available = true;
  bool editing = false;
  final form = GlobalKey<FormState>();
  String? message;
  Timer? timer;
  static const errors = {
    'WECHAT_DEBUG_NOT_ENABLED':
        '尚未检测到微信页面调试。请在微信内部打开调试入口，重新进入体育场馆页面后再检测；不需要重新配对',
    'SPORTS_PAGE_NOT_FOUND': '两分钟内未找到体育页面，请在微信完成登录并进入预约页面后重试',
    'OWNER_MISMATCH': '页面中的学校身份与填写的学号不一致，已拒绝保存',
    'READ_CHECK_HTTP_FAILED': '学校服务连接未完成，请联网后重试',
    'READ_CHECK_REJECTED': '学校未接受本次授权，请重新完成微信中的学校认证',
    'READ_CHECK_IDENTITY_REJECTED': '学校本人身份校验未通过，请重新进入学校体育页面并更新授权',
    'READ_CHECK_LOGIN_REJECTED': '学校未能建立体育会话，请重新进入学校体育页面并更新授权',
    'READ_CHECK_ORDERS_REJECTED': '学校未接受本人订单读取，请重新进入学校体育页面并更新授权',
    'SCOPED_SESSION_MISSING': '尚未取得可用于体育查询的会话',
    'IDENTITY_CONTEXT_MISMATCH': '学校返回的身份与页面上下文不一致，已拒绝保存',
    'ORDERS_OWNER_MISMATCH': '订单归属核对不一致，已拒绝保存',
    'CANCELLED': '已停止本次操作',
    'ACCOUNT_REQUIRED': '请填写本人的学号',
    'ADB_PORT_REQUIRED': '请填写无线调试主页面显示的连接端口',
    'ADB_NOT_CONNECTED': '本机无线调试未连接，请确认开关和连接端口',
    'ADB_CONNECTION_PORT_CLOSED': '无线调试连接端口已关闭或已变化，自动检测未找到新端口。请打开无线调试主页面，将“IP地址和端口”末尾的当前端口填入下方，再点“开始微信授权”；不需要重复配对。',
    'ADB_CONNECTION_TIMEOUT': '连接本机无线调试超时，请确认无线调试开启，并填写主页面当前连接端口后重试',
    'ADB_CONNECTION_TLS_FAILED': '本机无线调试加密连接失败，请确认使用的是主页面连接端口；仍失败时再重新配对',
    'ADB_PAIRING_REQUIRED': '系统未接受此 App 的调试身份，请重新配对本手机后再连接',
    'PAIRING_INPUT_REQUIRED': '请填写系统显示的配对端口和六位配对码',
    'PAIRING_FAILED': '系统配对未完成，请重新打开配对码窗口再试',
    'PAIRING_PORT_CLOSED': '配对端口已关闭或填错。请分屏保持系统配对码窗口打开，重新填写该窗口的端口（不是连接端口）',
    'PAIRING_TIMEOUT': '本机配对连接超时，请确认无线调试和配对码窗口仍开启',
    'PAIRING_TLS_FAILED': '系统配对加密连接未完成，请保持配对窗口打开并使用当前窗口的新配对码',
    'PAIRING_CODE_REJECTED': '系统未完成配对校验。请重新打开配对窗口，使用当前显示的配对码和端口',
    'PAIRING_CRYPTO_FAILED': '本机配对组件未能运行，请更新App后重试',
  };
  static const stages = {
    'PAIRING_LOCAL_ADB': '正在与手机系统配对…',
    'CONNECTING_LOCAL_ADB': '正在连接本机调试服务…',
    'DISCOVERING_LOCAL_ADB': '旧端口不可用，正在检测本机当前连接端口…',
    'FINDING_WECHAT_PAGE': '等待微信中的体育预约页面…',
    'READING_SPORTS_AUTHORIZATION': '已找到体育页面，正在读取授权…',
    'OWN_CONTEXT_READ': '已读取页面授权…',
    'CHECKING_OWN_IDENTITY_AND_ORDERS': '正在向学校核对本人身份和订单…',
    'CHECKING_OWN_IDENTITY': '正在向学校核对本人身份…',
    'DECODING_OWN_IDENTITY': '正在核对学校返回的身份…',
    'CREATING_SPORTS_SESSION': '正在建立体育会话…',
    'READING_OWN_ORDERS': '正在核对本人订单…',
    'SAVED': '已验证并加密保存',
    'WECHAT_DEBUG_READY': '已检测到微信页面调试，可以获取本人授权',
  };

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    refresh();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) refresh();
  }

  Future<void> refresh() async {
    final wasBusy = busy;
    try {
      final data = await channel
          .invokeMapMethod<String, dynamic>('status')
          .timeout(const Duration(seconds: 3));
      if (!mounted) return;
      if (wasBusy && !busy) return;
      setState(() {
        if (wasBusy) {
          final actualPort = data?['connection_port'];
          if (actualPort is int && actualPort > 0 &&
              actualPort != status['connection_port']) {
            connectionPort.text = '$actualPort';
          }
          status = {...status, 'capture_stage': data?['capture_stage'],
            if (actualPort is int && actualPort > 0) 'connection_port': actualPort,
            if (data?['adb_connected'] is bool) 'adb_connected': data!['adb_connected']};
          return;
        }
        final previousPort = status['connection_port'];
        status = data ?? {};
        if (student.text.isEmpty &&
            status['expected_student'] is String &&
            status['has_token'] != true) {
          student.text = status['expected_student'];
        }
        if ((connectionPort.text.isEmpty ||
                (busy && status['connection_port'] != previousPort)) &&
            (status['connection_port'] as int? ?? 0) > 0) {
          connectionPort.text = '${status['connection_port']}';
        }
      });
    } on MissingPluginException {
      if (mounted) setState(() => available = false);
    } on PlatformException {
      if (mounted && !busy) setState(() => message = '无法读取本机授权状态，请稍后重试');
    } on TimeoutException {
      if (mounted && !busy) setState(() => message = '读取本机状态超时，请重试');
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  Future<bool> operation(
      String method, Map<String, dynamic> args, String success) async {
    if (busy) return false;
    var succeeded = false;
    setState(() {
      busy = true;
      message = null;
    });
    timer?.cancel();
    timer = Timer.periodic(const Duration(seconds: 2), (_) {
      if (busy) refresh();
    });
    try {
      final data = await channel.invokeMapMethod<String, dynamic>(method, args);
      succeeded = true;
      if (mounted) {
        setState(() {
          status = data ?? {};
          final actualPort = status['connection_port'];
          if (actualPort is int && actualPort > 0) {
            connectionPort.text = '$actualPort';
          }
          message = success;
        });
      }
    } on PlatformException catch (error) {
      if (mounted) {
        setState(
            () => message = errors[error.code] ?? '本次操作未完成，未覆盖原有体育授权，请检查连接后重试');
      }
    } finally {
      timer?.cancel();
      timer = null;
      if (mounted) setState(() => busy = false);
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted && scroll.hasClients) {
          scroll.animateTo(scroll.position.maxScrollExtent,
              duration: const Duration(milliseconds: 200),
              curve: Curves.easeOut);
        }
      });
    }
    return succeeded;
  }

  Future<void> pair() async {
    final port = TextEditingController();
    final code = TextEditingController();
    final form = GlobalKey<FormState>();
    final accepted = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
              title: const Text('首次配对本机无线调试'),
              content: SingleChildScrollView(
                  child: Form(
                      key: form,
                      child: Column(mainAxisSize: MainAxisSize.min, children: [
                        const Text(
                            '请先分屏或悬浮显示系统设置和本App，再在系统“无线调试”中选择“使用配对码配对设备”。配对完成前必须保持系统配对码窗口打开，关闭后原端口和配对码会失效。这里只连接本手机。'),
                        const SizedBox(height: 12),
                        TextFormField(
                            controller: port,
                            keyboardType: TextInputType.text,
                            validator: (value) =>
                                localDebugPort(value ?? '') == null
                                    ? '请输入有效端口，也可粘贴系统显示的完整IP和端口'
                                    : null,
                            decoration:
                                const InputDecoration(labelText: '配对窗口中的端口')),
                        const SizedBox(height: 12),
                        TextFormField(
                            controller: code,
                            keyboardType: TextInputType.number,
                            obscureText: true,
                            enableSuggestions: false,
                            autocorrect: false,
                            validator: (value) => RegExp(r'^[0-9]{6}$')
                                    .hasMatch((value ?? '')
                                        .replaceAll(RegExp(r'\s'), ''))
                                ? null
                                : '请输入六位配对码',
                            decoration:
                                const InputDecoration(labelText: '六位配对码')),
                      ]))),
              actions: [
                TextButton(
                    onPressed: () => Navigator.pop(context, false),
                    child: const Text('返回')),
                FilledButton(
                    onPressed: () {
                      if (form.currentState!.validate()) {
                        Navigator.pop(context, true);
                      }
                    },
                    child: const Text('配对'))
              ],
            ));
    final pairPort = localDebugPort(port.text) ?? 0;
    final pairCode = code.text.replaceAll(RegExp(r'\s'), '');
    // Dialog fields are no longer displayed after this frame; no pairing code is saved.
    if (accepted == true && mounted) {
      await operation(
          'pair',
          {
            'pairing_port': pairPort,
            'code': pairCode,
          },
          '系统配对已完成。返回无线调试主页面，填写“IP地址和端口”中的连接端口。');
    }
    // AlertDialog disposes its text fields on the next frame.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      port.dispose();
      code.dispose();
    });
  }

  Future<void> capture() async {
    if (busy) return;
    if (!form.currentState!.validate()) return;
    FocusScope.of(context).unfocus();
    final succeeded = await operation(
        'capture',
        {
          'student': student.text.trim(),
          'connection_port': localDebugPort(connectionPort.text) ?? 0
        },
        '已取得本人授权并加密保存；重新建会话与本人订单查询通过。');
    if (succeeded && status['has_token'] == true) {
      student.clear();
      if (mounted) setState(() => editing = false);
    }
  }

  Future<void> copyDebugEntry() async {
    await Clipboard.setData(
        const ClipboardData(text: 'http://debugxweb.qq.com/?inspector=true'));
    if (mounted) {
      setState(() => message = '已复制。请在微信内部打开此地址，再重新进入学校体育场馆页；随后回到本App检测。');
    }
  }

  Future<void> checkDebug() async {
    final port = localDebugPort(connectionPort.text);
    if (port == null) {
      setState(() => message = '请先在下方填写无线调试主页面中的连接端口，再检测页面调试');
      return;
    }
    await operation(
        'check_debug', {'connection_port': port}, '已检测到微信页面调试，可以点击获取本人授权。');
  }

  @override
  void dispose() {
    timer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    if (available && busy) {
      channel.invokeMethod<void>('cancel').catchError((_) {});
    }
    student.dispose();
    connectionPort.dispose();
    scroll.dispose();
    super.dispose();
  }

  Future<void> openSettings() async {
    try {
      await channel.invokeMethod<void>('settings');
    } on PlatformException {
      if (mounted) setState(() => message = '无法打开系统设置，请手动进入“开发者选项 → 无线调试”');
    }
  }

  Future<void> forget() async {
    final confirmed = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
              title: const Text('删除本机体育授权？'),
              content: const Text(
                  '将暂停本机预约并删除手机中保存的体育授权；不撤销学校登录、不删除系统配对或学校已有预约。下次使用需重新获取授权。'),
              actions: [
                TextButton(
                    onPressed: () => Navigator.pop(context, false),
                    child: const Text('保留授权')),
                FilledButton(
                    onPressed: () => Navigator.pop(context, true),
                    child: const Text('删除授权')),
              ],
            ));
    if (confirmed != true || !mounted) return;
    await operation('forget', {}, '已删除本机体育授权；可在系统无线调试设置中单独撤销配对。');
    student.clear();
  }

  Future<void> showSetupHelp() => showDialog<void>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('首次设置帮助'),
          content: const SingleChildScrollView(
            child: Text(
                '1. 开启无线调试\n手机连接 Wi-Fi，在“关于手机”中连续点击版本号，开启开发者选项，再进入“开发者选项 → 无线调试”。\n\n'
                '2. 配对本手机\n在无线调试中选择“使用配对码配对设备”。分屏显示设置和本 App，保持配对窗口打开，在 App 中填写配对端口和六位配对码。配对码不会保存。\n\n'
                '3. 启用微信 XWeb 调试\n在微信内部打开 http://debugxweb.qq.com/?inspector=true ，然后退出并重新进入学校体育场馆页面。回到App点“检测微信页面调试”；未检测到时不要重新配对或反复获取授权。\n\n'
                '4. 填写连接端口\n关闭配对码窗口，回到无线调试主页面，找到“IP地址和端口”。这里的端口与配对端口不同。端口变化只需更新，不必重复配对。\n\n'
                '本机授权不需要电脑、抓包工具、代理证书或 Shizuku。完成后可关闭无线调试，更新授权时重新开启。\n\n'
                '独立授权实验 App 与本 App 数据隔离，旧授权和配对不能自动迁入；首次在此 App 中需要重新配对和获取。'),
          ),
          actions: [
            TextButton(
                onPressed: () => Navigator.pop(context),
                child: const Text('知道了')),
          ],
        ),
      );

  Widget section(String title, List<Widget> children) => Card(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child:
              Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Text(title, style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 12),
            ...children,
          ]),
        ),
      );

  @override
  Widget build(BuildContext context) {
    final saved = status['has_token'] == true;
    final paired = status['adb_paired'] == true;
    final showForm = !saved || editing;
    return PopScope(
      canPop: !busy,
      child: Scaffold(
        appBar: AppBar(title: const Text('体育授权')),
        body: SafeArea(
          child: loading
              ? const Center(child: CircularProgressIndicator())
              : ListView(
                  controller: scroll,
                  padding: const EdgeInsets.all(16),
                  children: [
                    const Text('从微信获取，无需导入文件',
                        style: TextStyle(
                            fontSize: 22, fontWeight: FontWeight.w700)),
                    const SizedBox(height: 8),
                    const Text(
                        '本人授权仅在手机内加密保存，不上传到项目服务器。本页只获取和校验本人授权，不会提交或取消学校预约。'),
                    const SizedBox(height: 16),
                    if (!available)
                      section('此安装包不支持本机授权', [
                        const Text('本功能需要 Android 11 及以上独立版，请检查当前安装包与系统版本。'),
                      ])
                    else ...[
                      if (saved)
                        section('授权已在本机加密保存', [
                          Text('学校身份：${status['owner']}'),
                          Text(
                              '最近校验：${DateTime.fromMillisecondsSinceEpoch(status['verified_at'] as int).toLocal()}'),
                          Text('本人订单查询返回 ${status['orders_count']} 条'),
                          const SizedBox(height: 8),
                          const Text(
                              '无需每次重新导入。学校未提供可靠有效期（使用app期间不要登陆微信小程序，避免影响授权有效性）；授权失效或切换学校账号时，再点击“更新授权”。真实预约能力尚需单独验证。'),
                          const SizedBox(height: 8),
                          Wrap(spacing: 8, children: [
                            OutlinedButton(
                                onPressed: busy
                                    ? null
                                    : () => setState(() => editing = !editing),
                                child: Text(editing ? '保留现有授权' : '更新授权')),
                            TextButton(
                                onPressed: busy ? null : forget,
                                child: const Text('删除本机体育授权')),
                          ]),
                        ]),
                      if (showForm) ...[
                        section(paired ? '1. 本机设置 · 已配对' : '1. 首次设置', [
                          Text(paired
                              ? '已配对，可直接填写连接端口。端口变化不必重新配对。'
                              : '开启无线调试 → 配对本手机 → 启用微信 XWeb 调试。只需设置一次。'),
                          const SizedBox(height: 12),
                          Wrap(spacing: 8, runSpacing: 8, children: [
                            OutlinedButton(
                                onPressed: busy ? null : openSettings,
                                child: const Text('打开系统设置')),
                            OutlinedButton(
                                onPressed: busy ? null : pair,
                                child: Text(paired ? '重新配对' : '首次系统配对')),
                          ]),
                          TextButton.icon(
                              onPressed: busy ? null : showSetupHelp,
                              icon: const Icon(Icons.help_outline),
                              label: const Text('查看详细设置帮助')),
                          const Text(
                              '配对后还需启用微信页面调试。在微信内部打开以下入口，再退出并重新进入学校体育场馆页面。不同微信版本的支持情况以检测结果为准。'),
                          const SelectableText(
                              'http://debugxweb.qq.com/?inspector=true'),
                          Wrap(spacing: 8, children: [
                            OutlinedButton(
                                onPressed: busy ? null : copyDebugEntry,
                                child: const Text('复制微信调试入口')),
                            OutlinedButton(
                                onPressed: busy ? null : checkDebug,
                                child: const Text('检测微信页面调试')),
                          ]),
                        ]),
                        section('2. 获取本人授权', [
                          const Text(
                              '回到无线调试主页面，查看“IP地址和端口”。连接端口不是配对码窗口的端口；端口变化时只需更新，不必重新配对。'),
                          const SizedBox(height: 12),
                          Form(
                            key: form,
                            child: Column(children: [
                              TextFormField(
                                key: const ValueKey('capture-port'),
                                controller: connectionPort,
                                enabled: !busy,
                                keyboardType: TextInputType.text,
                                autocorrect: false,
                                validator: (value) =>
                                    localDebugPort(value ?? '') == null
                                        ? '请填写有效的连接端口（1024–65535）'
                                        : null,
                                decoration: const InputDecoration(
                                  labelText: '无线调试连接端口',
                                  hintText: '例如 35945',
                                  helperText: '也可粘贴完整 IP:端口；始终只连接本手机',
                                  helperMaxLines: 3,
                                  errorMaxLines: 3,
                                ),
                              ),
                              const SizedBox(height: 16),
                              TextFormField(
                                key: const ValueKey('capture-student'),
                                controller: student,
                                enabled: !busy,
                                keyboardType: TextInputType.number,
                                autocorrect: false,
                                enableSuggestions: false,
                                validator: (value) => RegExp(r'^[0-9]{6,24}$')
                                        .hasMatch((value ?? '').trim())
                                    ? null
                                    : '请先填写本人的学号，用于核对学校身份',
                                decoration: const InputDecoration(
                                    labelText: '本人学号',
                                    helperText: '只获取与你填写的学号一致的授权',
                                    helperMaxLines: 3,
                                    errorMaxLines: 3),
                              ),
                            ]),
                          ),
                          const SizedBox(height: 16),
                          const Text(
                              '点击后将打开微信。进入厦大体育预约的场馆首页或时段选择页，完成学校登录，再返回本 App 查看结果。仅打开微信不代表授权成功。'),
                          const SizedBox(height: 12),
                          FilledButton.icon(
                            icon: const Icon(Icons.key_outlined),
                            onPressed: busy ? null : capture,
                            label: Text(busy
                                ? stages[status['capture_stage']] ?? '正在处理本次授权…'
                                : '开始微信授权'),
                          ),
                          if (busy) ...[
                            const Padding(
                                padding: EdgeInsets.symmetric(vertical: 12),
                                child: LinearProgressIndicator()),
                            const Text('正在获取并核对本人身份及订单，最多等待两分钟。完成前不会覆盖原有授权。'),
                            OutlinedButton(
                                onPressed: () =>
                                    channel.invokeMethod<void>('cancel'),
                                child: const Text('停止本次操作')),
                          ],
                        ]),
                        section('3. 自动核对并保存', [
                          const Text(
                              '校验页面学号、学校身份与本人订单后自动保存，只显示脱敏结果。失败时保留已有授权，修正提示的问题后可重试。'),
                        ]),
                      ],
                    ],
                    if (message != null)
                      Semantics(
                          liveRegion: true,
                          child: Padding(
                            padding: const EdgeInsets.symmetric(vertical: 16),
                            child: Text(message!,
                                key: const ValueKey('capture-message')),
                          )),
                  ],
                ),
        ),
      ),
    );
  }
}
