import { loadConfig } from './config.js';
import { createApp } from './app.js';

const config = loadConfig();
const server = createApp(config);
server.listen(config.port, () => console.log(`Billy backend listening on :${config.port}${config.basePath}`));
for (const sig of ['SIGINT', 'SIGTERM']) process.on(sig, () => server.close(() => process.exit(0)));
