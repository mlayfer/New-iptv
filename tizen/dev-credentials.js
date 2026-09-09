/*
 * Optional sign-in details for a development build. The file is committed empty
 * on purpose: the repository is public, so nothing secret may live here. A build
 * fills it in from GitHub secrets (see .github/workflows/android.yml), and the
 * app skips the setup screen whenever it finds values here.
 */
window.TalohimDev = null;
// window.TalohimDev = { server: 'http://example.com:80', user: '', pass: '' };
