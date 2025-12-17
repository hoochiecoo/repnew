import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() => runApp(const MyApp());

class MyApp extends StatelessWidget {
  const MyApp({super.key});
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      theme: ThemeData.dark(),
      home: const CameraScreen(),
    );
  }
}

class CameraScreen extends StatefulWidget {
  const CameraScreen({super.key});
  @override
  State<CameraScreen> createState() => _CameraScreenState();
}

class _CameraScreenState extends State<CameraScreen> {
  static const MethodChannel _methodChannel = MethodChannel('com.example.camera/methods');
  static const EventChannel _eventChannel = EventChannel('com.example.camera/events');
  
  int? _textureId;
  bool _lineDetected = false;
  int _lineLength = 0;
  String _status = "Initializing...";

  @override
  void initState() {
    super.initState();
    _startCamera();
    _eventChannel.receiveBroadcastStream().listen((dynamic event) {
      if (!mounted) return;
      
      // Получаем данные от Kotlin
      if (event is Map) {
        final bool detected = event['detected'] ?? false;
        final int length = event['length'] ?? 0;
        
        setState(() {
          _lineDetected = detected;
          _lineLength = length;
          _status = detected ? "Line Detected!" : "Scanning...";
        });
      }
    }, onError: (e) {
      setState(() => _status = "Error: $e");
    });
  }

  Future<void> _startCamera() async {
    try {
      final tid = await _methodChannel.invokeMethod('startCamera');
      if (mounted) setState(() => _textureId = tid);
    } catch (e) {
      if (mounted) setState(() => _status = "Permission Error or Crash: $e");
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text("Line Detector Cam")),
      body: Stack(
        children: [
          // 1. Слой Камеры
          Positioned.fill(
            child: _textureId == null 
                ? const Center(child: CircularProgressIndicator())
                : Texture(textureId: _textureId!),
          ),
          
          // 2. Слой Подсветки (Highlighter)
          if (_lineDetected)
            Center(
              child: Container(
                height: 4, // Толщина линии
                width: double.infinity,
                margin: const EdgeInsets.symmetric(horizontal: 20),
                decoration: BoxDecoration(
                  color: Colors.redAccent,
                  boxShadow: [
                    BoxShadow(color: Colors.red.withOpacity(0.8), blurRadius: 10, spreadRadius: 2)
                  ]
                ),
              ),
            ),

          // 3. Инфо-панель
          Positioned(
            bottom: 30,
            left: 20,
            right: 20,
            child: Container(
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: _lineDetected ? Colors.red.withOpacity(0.8) : Colors.black54,
                borderRadius: BorderRadius.circular(12),
              ),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    _status,
                    style: const TextStyle(fontSize: 20, fontWeight: FontWeight.bold, color: Colors.white),
                  ),
                  if (_lineDetected)
                    Text(
                      "Length: $_lineLength px",
                      style: const TextStyle(color: Colors.white70),
                    )
                ],
              ),
            ),
          )
        ],
      ),
    );
  }
}
