# Capturing the admin UI pictures

`plugin-config.png` and `data-browser.png` are real screenshots of a Signal K server with the
plugin installed, not drawings. To take them again after a change, on a machine with Node 24:

1. A scratch server, apart from any real installation:
   ```sh
   mkdir sk && cd sk && npm init -y && npm install signalk-server@2.31.1   # the version in the checked-in pictures
   mkdir config && cd config && npm init -y && npm install /path/to/android-push-to-talk/sk-plugin
   ```
   `defaults.json` with the vessel name, `settings.json` with `"security": {"strategy": ""}` (no
   login on a scratch server), and `plugin-config-data/signalk-crewradio.json` with
   `{"enabled": true, "configuration": {"channelKey": "...", "nodeName": "Sirius"}}`.
   If `/admin/` answers "Could not handle admin ui root request", the server looks for
   `@signalk/server-admin-ui` inside its own `node_modules`; copy the hoisted package there.
2. Start it: `node node_modules/signalk-server/bin/signalk-server -c ./config`.
3. Give it something to show: two channel nodes,
   `node tools/cli.js roster --key KEY --name Skipper --seconds 90` and the same with `--name Mate`
   (KEY as in the plugin's config; `--iface` if the machine has several adapters), and
   `curl -X POST http://localhost:3000/plugins/signalk-crewradio/say -H 'Content-Type: application/json' -d '{"text":"..."}'`
   so the status line reads "announcing" while the picture is taken.
4. Capture at 1280 x 800 with a headless Chromium browser over the DevTools protocol (the
   `--screenshot` flag proved unreliable on Windows):
   `http://localhost:3000/admin/#/serverConfiguration/plugins/signalk-crewradio` and
   `http://localhost:3000/admin/#/databrowser`, waiting a few seconds after navigation for the
   React app to fetch its data.

The vessel and node names in the pictures are examples; no real device name is in them.
