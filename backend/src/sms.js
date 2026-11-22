/** Sends SMS through Twilio's REST API, or logs to the console when no credentials are configured. */
export function createSmsSender(twilio, log = console) {
  const configured = twilio.sid && twilio.token && (twilio.from || twilio.messagingServiceSid);
  if (!configured) {
    return {
      async send(to, body) {
        log.log(`[sms:dev] to=${to}\n${body}`);
      },
    };
  }
  const url = `https://api.twilio.com/2010-04-01/Accounts/${encodeURIComponent(twilio.sid)}/Messages.json`;
  const auth = 'Basic ' + Buffer.from(`${twilio.sid}:${twilio.token}`).toString('base64');
  return {
    async send(to, body) {
      const form = new URLSearchParams({ To: to, Body: body });
      if (twilio.messagingServiceSid) form.set('MessagingServiceSid', twilio.messagingServiceSid);
      else form.set('From', twilio.from);
      const res = await fetch(url, {
        method: 'POST',
        headers: { Authorization: auth, 'Content-Type': 'application/x-www-form-urlencoded' },
        body: form,
        signal: AbortSignal.timeout(10_000),
      });
      if (!res.ok) throw new Error(`Twilio responded ${res.status}`);
    },
  };
}
