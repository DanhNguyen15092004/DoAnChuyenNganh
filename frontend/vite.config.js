import { defineConfig, loadEnv } from 'vite';

export default defineConfig(({ mode, command }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const target = process.env.BACKEND_TARGET || env.BACKEND_TARGET;
  if (command === 'serve' && (!target || !/^https?:\/\//.test(target))) {
    throw new Error('Đặt BACKEND_TARGET trong frontend/.env.local (xem .env.example).');
  }
  return {
    server: command === 'serve' ? {
      port: 5173, strictPort: true,
      proxy: { '/api': { target, changeOrigin: true } },
    } : undefined,
  };
});
