// Anchor a monotonic elapsed time to the server sample; never trust the PC wall clock.
export function remainingMilliseconds(attempt, receivedAt, monotonicNow) {
  if (!attempt || attempt.availability !== 'ACTIVE') return 0;
  const serverBudget = Date.parse(attempt.deadlineAt) - Date.parse(attempt.serverTime);
  return Math.max(0, serverBudget - Math.max(0, monotonicNow - receivedAt));
}
export function formatRemaining(milliseconds) {
  const seconds = Math.max(0, Math.ceil(milliseconds / 1000));
  const pad = value => String(value).padStart(2, '0');
  return `${pad(Math.floor(seconds / 3600))}:${pad(Math.floor(seconds / 60) % 60)}:${pad(seconds % 60)}`;
}
