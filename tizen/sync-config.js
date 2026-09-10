/*
 * Optional pairing details for a personal build, so a television and a phone
 * share one memory. Committed empty on purpose: this repository is public.
 *
 * `url` and `key` are the project URL and the anon key of a Supabase project
 * you own — the anon key is meant to sit inside a client, and the table's
 * policy is what protects it. `room` is the shared secret that names your row:
 * make it long and random, because anyone who has it can read your history.
 *
 * The same three values go on every device you want paired. See tizen/README.md
 * for the table and its policy.
 */
window.TalohimSync = null;
// window.TalohimSync = { url: 'https://xxxx.supabase.co', key: 'eyJ...', room: '' };
