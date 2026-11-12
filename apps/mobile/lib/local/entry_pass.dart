import 'package:flutter/material.dart';
import 'package:qr_flutter/qr_flutter.dart';
import 'local_api.dart';

/// Fetch and retain entry credentials only while the user opens an order.
class EntryPassView extends StatefulWidget {
  const EntryPassView({super.key, required this.orderId});
  final String orderId;
  @override
  State<EntryPassView> createState() => _EntryPassViewState();
}

class _EntryPassViewState extends State<EntryPassView> {
  late Future<Json> result;
  @override
  void initState() {
    super.initState();
    result = const LocalApi().orderEntryPass(widget.orderId);
  }

  @override
  Widget build(BuildContext context) => Padding(
      padding: const EdgeInsets.symmetric(vertical: 16),
      child: FutureBuilder<Json>(future: result, builder: (context, snapshot) {
        if (snapshot.connectionState != ConnectionState.done) {
          return const Column(children: [SizedBox(width: 24, height: 24,
              child: CircularProgressIndicator(strokeWidth: 2)), SizedBox(height: 8), Text('正在获取学校入场二维码…')]);
        }
        if (snapshot.hasError) {
          return Column(children: [Text(localError(snapshot.error!)),
            TextButton(onPressed: () => setState(() => result = const LocalApi().orderEntryPass(widget.orderId)),
                child: const Text('重新获取入场码'))]);
        }
        final passes = ((snapshot.data?['passes'] as List?) ?? []).cast<Json>();
        return Column(children: [
          Text('入场二维码', style: Theme.of(context).textTheme.titleMedium),
          for (final pass in passes) ...[
            Text('${pass['date']} ${pass['range']} · ${pass['area_name']}'),
            LayoutBuilder(builder: (context, constraints) => QrImageView(
              data: '${pass['payload']}', version: QrVersions.auto,
              size: constraints.maxWidth.clamp(0, 280), padding: const EdgeInsets.all(48),
              backgroundColor: Colors.white, errorCorrectionLevel: QrErrorCorrectLevel.H,
              semanticsLabel: '学校入场二维码')),
          ],
          const Text('到场后出示此码扫码入场'),
        ]);
      }));
}
