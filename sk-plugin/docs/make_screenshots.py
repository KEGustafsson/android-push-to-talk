"""Draws the two illustrative App Store pictures and the icon for signalk-crewradio as draw.io files,
the way docs/diagrams/make_diagrams.py draws the app's screens: mock-ups with example names, no real
device names. The admin UI pictures (plugin-config.png, data-browser.png) are real captures of a
Signal K server with the plugin installed, taken with docs/capture_admin_ui.md's recipe. Export to PNG with draw.io desktop from the repository root:

    python sk-plugin/docs/make_screenshots.py
    for n in announcement how-it-fits; do
      "C:/Program Files/draw.io/draw.io.exe" -x -f png -s 1 -b 0 -o sk-plugin/docs/screenshots/$n.png sk-plugin/docs/diagrams/$n.drawio
    done
    "C:/Program Files/draw.io/draw.io.exe" -x -f png -s 1 -b 0 -o sk-plugin/docs/icon.png sk-plugin/docs/diagrams/icon.drawio

Each screenshot is 1280 x 800 (the App Store's recommended 16:10), the icon 128 x 128.
"""
import html
import os

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "diagrams")
W, H = 1280, 800

# The app's dark theme (as in make_diagrams.py) and a light one for the diagram and the admin UI.
BG, CARD, OUTLINE = "#0F1418", "#151B20", "#2C363C"
CYAN, CYAN_DIM, TEXT, MUTED, TALKING = "#4DD0E1", "#123A40", "#E6EEF0", "#8A9AA0", "#81C784"
MONO = "fontFamily=Courier New;"
SANS = "fontFamily=Helvetica;"
LIGHT_BG, LIGHT_CARD, LIGHT_LINE, INK, INK_MUTED = "#F4F6F8", "#FFFFFF", "#D0D7DC", "#1B2A33", "#5C6B75"
SK_BLUE = "#0B6EC7"


def diagram(name, nodes, edges=(), width=W, height=H, background=None):
    cells = ['<mxCell id="0"/>', '<mxCell id="1" parent="0"/>']
    for nid, label, x, y, w, h, style in nodes:
        cells.append(
            f'<mxCell id="{nid}" value="{html.escape(label, quote=True).replace(chr(10), "&lt;br&gt;")}" style="{style}" vertex="1" parent="1">'
            f'<mxGeometry x="{x}" y="{y}" width="{w}" height="{h}" as="geometry"/></mxCell>'
        )
    for i, (src, dst, label, style, *rest) in enumerate(edges):
        points = rest[0] if rest else []
        geo = '<mxGeometry relative="1" as="geometry">'
        if points:
            geo += '<Array as="points">' + "".join(f'<mxPoint x="{px}" y="{py}"/>' for px, py in points) + "</Array>"
        geo += "</mxGeometry>"
        cells.append(
            f'<mxCell id="e{i}" value="{html.escape(label, quote=True)}" style="{style}" edge="1" parent="1" '
            f'source="{src}" target="{dst}">{geo}</mxCell>'
        )
    bg = f' background="{background}"' if background else ""
    xml = (
        '<mxfile host="Electron" type="device">'
        f'<diagram id="{name}" name="{name}">'
        f'<mxGraphModel dx="1" dy="1" grid="1" gridSize="10" guides="1" tooltips="1" connect="1" arrows="1" '
        f'fold="1" page="1" pageScale="1" pageWidth="{width}" pageHeight="{height}" math="0" shadow="0"{bg}>'
        "<root>" + "".join(cells) + "</root></mxGraphModel></diagram></mxfile>"
    )
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, name + ".drawio"), "w", encoding="utf-8") as f:
        f.write(xml)
    print("wrote", name + ".drawio")


def txt(nid, s, x, y, w, h, size=12, color=TEXT, bold=False, align="left", mono=True, valign="middle"):
    st = (f"text;html=1;whiteSpace=wrap;align={align};verticalAlign={valign};fontSize={size};fontColor={color};"
          + (MONO if mono else SANS) + ("fontStyle=1;" if bold else "") + "spacing=0;")
    return (nid, s, x, y, w, h, st)


def rect(nid, x, y, w, h, fill=CARD, stroke=OUTLINE, arc=12, width=1, label="", color=TEXT, size=12, mono=True, bold=False):
    st = (f"rounded=1;arcSize={arc};whiteSpace=wrap;html=1;fillColor={fill};strokeColor={stroke};strokeWidth={width};"
          f"fontSize={size};fontColor={color};" + (MONO if mono else SANS) + ("fontStyle=1;" if bold else ""))
    return (nid, label, x, y, w, h, st)


def ellipse(nid, x, y, w, h, fill, stroke, width=1, label="", color=TEXT, size=12):
    return (nid, label, x, y, w, h, f"ellipse;whiteSpace=wrap;html=1;fillColor={fill};strokeColor={stroke};strokeWidth={width};fontSize={size};fontColor={color};" + SANS + "fontStyle=1;")


def toggle(nid, x, y, on, scale=1.0):
    w, h = 56 * scale, 28 * scale
    track = rect(nid + "t", x, y, w, h, fill=(CYAN if on else "#3A444A"), stroke=(CYAN if on else "#3A444A"), arc=50)
    kx = x + (30 if on else 4) * scale
    knob = (nid + "k", "", kx, y + 4 * scale, 20 * scale, 20 * scale,
            f"ellipse;whiteSpace=wrap;html=1;fillColor={'#0B3A40' if on else '#8A9AA0'};strokeColor=none;")
    return [track, knob]


def phone(p, x, y, talker, peers, s=1.0):
    """The app's main screen, on channel, with the server talking. `s` scales the 360 x 760 frame."""
    n = [(p + "f", "", x, y, 360 * s, 760 * s, f"rounded=1;arcSize=8;whiteSpace=wrap;html=1;fillColor={BG};strokeColor=#3A444A;strokeWidth=2;")]
    n.append(txt(p + "h1", "CHANNEL", x + 24 * s, y + 40 * s, 200 * s, 20 * s, int(11 * s), MUTED))
    n.append(txt(p + "h2", "CREW RADIO", x + 22 * s, y + 60 * s, 220 * s, 40 * s, int(26 * s), CYAN, bold=True, mono=False))
    n.append(rect(p + "hc", x + 232 * s, y + 52 * s, 66 * s, 40 * s, arc=25, label="▮▮ " + peers, color=TALKING, size=int(14 * s)))
    n.append(txt(p + "m", "⋮", x + 316 * s, y + 52 * s, 30 * s, 40 * s, int(22 * s), TEXT, align="center"))
    for i, (name, active) in enumerate([("WLAN", True), ("BLUETOOTH", True), ("AWARE", True)]):
        tx = x + (20 + i * 108) * s
        n.append(rect(p + f"t{i}", tx, y + 118 * s, 100 * s, 66 * s, fill=(CYAN_DIM if active else BG), stroke=(CYAN if active else OUTLINE), arc=18,
                      label=name, color=(CYAN if active else MUTED), size=int(11 * s)))
    row_y = y + 202 * s
    n.append(rect(p + "sw", x + 20 * s, row_y, 320 * s, 50 * s))
    n.append(txt(p + "swl", "ON CHANNEL", x + 36 * s, row_y + 8 * s, 200 * s, 34 * s, int(13 * s), CYAN))
    n += toggle(p + "sw", x + 276 * s, row_y + 11 * s, True, s)
    n.append(txt(p + "tk", "● " + talker + " TALKING", x + 24 * s, row_y + 62 * s, 320 * s, 20 * s, int(11 * s), TALKING))
    cx, cy, r = x + 180 * s, y + 555 * s, 150 * s
    n.append((p + "d", "", cx - r, cy - r, 2 * r, 2 * r, f"ellipse;whiteSpace=wrap;html=1;fillColor={CYAN};strokeColor=#2C363C;strokeWidth=10;"))
    n.append(txt(p + "d1", "TALK", cx - r, cy - 40 * s, 2 * r, 50 * s, int(44 * s), "#00343A", bold=True, align="center", mono=False))
    n.append(txt(p + "d2", "HOLD", cx - r, cy + 12 * s, 2 * r, 24 * s, int(13 * s), "#00494F", align="center"))
    return n


# ---------------------------------------------------------------- 1. announcement (dark)

def announcement():
    n = [rect("bg", 0, 0, W, H, fill=BG, stroke="none", arc=0)]
    n.append(txt("title", "The boat speaks on the crew channel", 60, 44, 800, 44, 30, TEXT, bold=True, mono=False))
    n.append(txt("sub", "A Signal K alarm, said by the boat's voice assistant, heard on every phone of the crew.", 60, 90, 760, 30, 16, MUTED, mono=False))
    # the phone, at 0.8
    n += phone("ph", 820, 100, "SIRIUS", "3", 0.8)
    # the server side: a chain of cards
    y = 160
    cards = [
        ("notif", "Signal K notification", "notifications.navigation.anchor\nstate: alarm · method: sound\n\"Anchor is dragging, 25 m\"", "#FFB4AB"),
        ("bridge", "signalk-crewradio · bridge", "at or above alarm, asks for sound\n→ say({ text, priority })\nrepeats every 30 s until it clears", CYAN),
        ("wy", "signalk-crewradio · voice", "Flite text-to-speech, 16 kHz, inside the plugin\nthe announcement queue: urgent first", CYAN),
        ("sat", "signalk-crewradio · channel node", "a chime in front, waits for a gap,\nkeys the channel like a talker", CYAN),
        ("wlan", "Boat network → phones", "LAN or WLAN, the app's own packets, AES-256-GCM\nrelayed on over Bluetooth and Wi-Fi Aware", TALKING),
    ]
    for i, (nid, head, body, color) in enumerate(cards):
        cy = y + i * 118
        n.append(rect(nid, 60, cy, 700, 100, fill=CARD, stroke=OUTLINE, arc=10))
        n.append(txt(nid + "h", head, 80, cy + 8, 660, 26, 15, color, bold=True, mono=False))
        n.append(txt(nid + "b", body, 80, cy + 34, 660, 62, 13, TEXT, valign="top"))
        if i < len(cards) - 1:
            n.append(txt(nid + "a", "↓", 60, cy + 100, 700, 18, 16, MUTED, align="center"))
    n.append(txt("foot", "Data browser: communication.crewradio.online = 3 · .talking = [\"Sirius\"]", 60, H - 52, 740, 24, 13, MUTED))
    diagram("announcement", n, background=BG)


# ---------------------------------------------------------------- 2. how it fits (light)

def how_it_fits():
    def box(nid, label, x, y, w, h, fill, stroke, size=14, bold=False):
        return rect(nid, x, y, w, h, fill=fill, stroke=stroke, arc=10, width=1.5, label=label, color=INK, size=size, mono=False, bold=bold)
    edge = "edgeStyle=orthogonalEdgeStyle;rounded=1;html=1;endArrow=block;endFill=1;strokeColor=#455A64;strokeWidth=2;"
    dashed = "edgeStyle=orthogonalEdgeStyle;rounded=1;html=1;endArrow=none;dashed=1;strokeColor=#78909C;strokeWidth=2;"
    def cap(nid, label, x, y, w, h=22, size=12, align="center"):
        return txt(nid, label, x, y, w, h, size, INK_MUTED, mono=False, align=align)
    n = [rect("bg", 0, 0, W, H, fill=LIGHT_BG, stroke="none", arc=0)]
    n.append(txt("title", "How signalk-crewradio fits in", 60, 36, 900, 44, 30, INK, bold=True, mono=False))
    n.append(txt("sub", "The Signal K server becomes one more node on the crew's channel, and speaks to it in its own voice.", 60, 82, 1100, 30, 16, INK_MUTED, mono=False))

    # left: where announcements come from
    n.append(box("alarms", "Alarms in Signal K\nanchor drag · man overboard · AIS · engine · depth", 60, 200, 330, 100, "#FFF3E0", "#EF6C00"))
    n.append(box("say", "Anything that can speak\nNode-RED · dashboards · other plugins\nPUT voice.say · REST /api/say", 60, 400, 330, 110, "#F3E5F5", "#7B1FA2"))

    # middle: the server
    n.append(rect("srv", 450, 150, 400, 560, fill=LIGHT_CARD, stroke=LIGHT_LINE, arc=14, width=1.5))
    n.append(txt("srvt", "Signal K server", 470, 158, 360, 30, 16, INK_MUTED, bold=True, mono=False))
    n.append(box("cr", "signalk-crewradio\nnotification bridge · say() doors\nchannel node · roster", 480, 200, 340, 110, "#E0F7FA", "#00838F", 14, True))
    n.append(box("wy", "announcement queue\nurgent first, cuts a normal one short", 480, 400, 340, 90, "#E8F5E9", "#2E7D32"))
    n.append(box("piper", "Flite text-to-speech (WebAssembly)\nEnglish, 16 kHz, inside the plugin", 480, 560, 340, 80, "#E8F5E9", "#2E7D32"))
    n.append(cap("c1", "say(text, urgent)", 400, 340, 150, align="right"))
    n.append(cap("c2", "speech", 760, 340, 120, align="left"))
    n.append(cap("c3", "text · 16 kHz PCM", 560, 510, 180))
    n.append(cap("srvpaths", "publishes communication.crewradio.online · nodes · talking", 460, 668, 380, 22, 12))

    # right: the crew, on the boat network (the server may be on the wired side of the same router)
    n.append(("wlan", "", 910, 150, 330, 560, "rounded=1;arcSize=6;whiteSpace=wrap;html=1;fillColor=none;strokeColor=#0288D1;strokeWidth=1.5;dashed=1;"))
    n.append(txt("wlant", "boat network: LAN / WLAN", 930, 158, 290, 30, 16, "#0288D1", bold=True, mono=False))
    for i, (name, y) in enumerate([("Skipper's phone", 220), ("Mate's phone", 380), ("Deck phone", 540)]):
        n.append(box(f"p{i}", name + "\nCrew Radio app", 950, y, 250, 90, "#E1F5FE", "#0288D1", 14, True))
    n.append(cap("c4", "16 kHz PCM · hellos · AES-256-GCM", 850, 262, 200, 44, 12))
    n.append(cap("c5", "the phones relay on over Bluetooth and Wi-Fi Aware", 930, 660, 290, 36, 12))
    n.append(cap("legend", "no cloud, no containers, no other plugin: the voice is made inside the plugin; the phones hear the same packets as from any crew member", 60, 740, 1160, 24, 12))

    e = [
        ("alarms", "cr", "", edge),
        ("say", "cr", "", edge, [(420, 455), (420, 300)]),
        ("cr", "wy", "", edge, [(560, 355)]),                 # left lane, down
        ("wy", "cr", "", edge, [(740, 355)]),                 # right lane, up
        ("wy", "piper", "", edge + "startArrow=block;startFill=1;"),
        ("cr", "p0", "", edge, [(880, 255), (880, 265)]),
        ("p0", "p1", "", dashed, [(1220, 265), (1220, 425)]),
        ("p1", "p2", "", dashed, [(1220, 425), (1220, 585)]),
    ]
    diagram("how-it-fits", n, e, background=LIGHT_BG)


# ---------------------------------------------------------------- 4. icon

def icon():
    """A speech waveform in the app's talk-button colours, 128 x 128."""
    n = [rect("bg", 0, 0, 128, 128, fill=BG, stroke="none", arc=22)]
    n.append(ellipse("disc", 12, 12, 104, 104, CYAN, "none"))
    for i, h in enumerate([22, 44, 66, 44, 22]):
        x = 36 + i * 13
        n.append(rect(f"b{i}", x, 64 - h // 2, 9, h, fill="#00343A", stroke="none", arc=50))
    diagram("icon", n, width=128, height=128, background=BG)


if __name__ == "__main__":
    announcement()
    how_it_fits()
    icon()
