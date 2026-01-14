const WebSocket = require('ws');

const serverUrl = 'ws://localhost:8080';
const ws = new WebSocket(serverUrl);

ws.on('open', function open() {
    console.log('Connected to signaling server');

    // Test ping
    console.log('Sending ping...');
    ws.send(JSON.stringify({ type: 'ping' }));

    // Test join
    console.log('Sending join message with dummy MAC...');
    ws.send(JSON.stringify({ type: 'join', mac: 'AA:BB:CC:DD:EE:FF' }));
});

ws.on('message', function incoming(data) {
    const message = JSON.parse(data);
    console.log('Received:', message);

    if (message.type === 'joined') {
        console.log('SUCCESS: Signaling server is responding correctly to join messages.');
        ws.close();
    }
    if (message.type === 'pong') {
        console.log('SUCCESS: Signaling server is responding to pings.');
    }
});

ws.on('error', function error(err) {
    console.error('Connection error:', err.message);
    console.log('Make sure the server is running on port 8080.');
});

ws.on('close', function close() {
    console.log('Disconnected from signaling server');
});
