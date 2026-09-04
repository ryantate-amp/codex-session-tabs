# Marketplace content

The plugin description is maintained in
[`src/main/resources/META-INF/plugin.xml`](../src/main/resources/META-INF/plugin.xml). JetBrains
Marketplace and the IDE's plugin manager read it from the uploaded plugin archive.

The Marketplace **Getting Started** section is an admin-panel field and has no corresponding
`plugin.xml` element. Paste the following HTML into that field:

```html
<ol>
  <li>Install Codex Session Tabs and restart the IDE if prompted.</li>
  <li>In <strong>Settings → Tools → Terminal</strong>, select the Reworked Terminal engine.</li>
  <li>Open a terminal tab, then start or resume Codex normally.</li>
  <li>Leave Codex tabs open when exiting the IDE to restore those sessions on the next launch.</li>
</ol>
<p>
  No configuration is required. Session names and the activity indicator may take a few seconds to
  appear after Codex starts.
</p>
<p>
  <strong>Requirements:</strong> a JetBrains IDE based on build 262 (2026.2) or newer, the Codex CLI,
  and <code>lsof</code> on the system path.
</p>
```
