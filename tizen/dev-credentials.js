/*
 * Optional sign-in details for a personal build. The file is committed empty on
 * purpose: this repository is public, so nothing secret may live here. A build
 * fills it in from GitHub secrets (see .github/workflows/android.yml), and the
 * app skips the setup screen whenever it finds values here. The package that
 * carries them is a workflow artifact, never a public release asset.
 */
window.TalohimDev = null;
// window.TalohimDev = { servers: ['http://example.com:80'], user: '', pass: '' };
