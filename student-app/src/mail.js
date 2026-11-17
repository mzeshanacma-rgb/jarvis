import nodemailer from 'nodemailer';

let transport = null;

// With SMTP_URL set, email is sent; otherwise (development) it is printed to the console.
export async function sendMail(cfg, { to, subject, text }) {
  if (!cfg.smtp) {
    if (process.env.NODE_ENV !== 'test') console.log(`[mail] (SMTP not set) to=${to} subject=${subject}\n${text}`);
    return { logged: true };
  }
  transport ||= nodemailer.createTransport(cfg.smtp);
  await transport.sendMail({ from: cfg.mailFrom, to, subject, text });
  return { sent: true };
}
