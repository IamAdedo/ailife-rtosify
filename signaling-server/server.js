const WebSocket = require('ws');

const wss = new WebSocket.Server({ port: 8080 });

// Map to store connected clients: macAddress -> WebSocket
const clients = new Map();

console.log('Signaling server started on port 8080');

wss.on('connection', (ws) => {
    console.log('New client connected');
    let clientMac = null;

    ws.on('message', (message) => {
        try {
            const data = JSON.parse(message);
            
            switch (data.type) {
                case 'join':
                    // Client registering its MAC address
                    if (data.mac) {
                        clientMac = data.mac.toUpperCase();
                        clients.set(clientMac, ws);
                        console.log(`Client registered: ${clientMac}`);
                        ws.send(JSON.stringify({ type: 'joined', mac: clientMac }));
                    }
                    break;

                case 'signal':
                    // Relaying signal to target device
                    if (data.target && data.payload) {
                        const targetMac = data.target.toUpperCase();
                        const targetWs = clients.get(targetMac);
                        
                        if (targetWs && targetWs.readyState === WebSocket.OPEN) {
                            console.log(`Relaying signal from ${clientMac} to ${targetMac}`);
                            targetWs.send(JSON.stringify({
                                type: 'signal',
                                source: clientMac,
                                payload: data.payload
                            }));
                        } else {
                            console.log(`Target ${targetMac} not found or offline`);
                            // Optionally notify sender
                        }
                    }
                    break;
                    
                case 'ping':
                    ws.send(JSON.stringify({ type: 'pong' }));
                    break;

                default:
                    console.log('Unknown message type:', data.type);
            }
        } catch (e) {
            console.error('Error parsing message:', e);
        }
    });

    ws.on('close', () => {
        if (clientMac) {
            console.log(`Client disconnected: ${clientMac}`);
            clients.delete(clientMac);
        }
    });

    ws.on('error', (err) => {
        console.error('WebSocket error:', err);
    });
});
