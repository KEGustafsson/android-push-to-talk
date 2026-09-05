"""Draws the App Store screenshots and the icon for signalk-crewradio as draw.io files, the way
docs/diagrams/make_diagrams.py draws the app's screens: mock-ups with example names, no real
device names. Export to PNG with draw.io desktop from the repository root:

    python sk-plugin/docs/make_screenshots.py
    for n in announcement how-it-fits settings; do
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
    n += phone("ph", 820, 100, "ARABELLA", "3", 0.8)
    # the server side: a chain of cards
    y = 160
    cards = [
        ("notif", "Signal K notification", "notifications.navigation.anchor\nstate: alarm · method: sound\n\"Anchor is dragging, 25 m\"", "#FFB4AB"),
        ("bridge", "signalk-crewradio · bridge", "at or above alarm, asks for sound\n→ say({ text, priority, targets: [\"crewradio\"] })\nrepeats every 30 s until it clears", CYAN),
        ("wy", "signalk-wyoming · Piper", "text-to-speech, the announcement queue\nurgent jumps every queue", CYAN),
        ("sat", "signalk-crewradio · satellite", "22 050 Hz → 16 kHz, a chime in front,\nwaits for a gap, keys the channel", CYAN),
        ("wlan", "Boat WLAN → phones", "the app's own packets, AES-256-GCM\nrelayed on over Bluetooth and Wi-Fi Aware", TALKING),
    ]
    for i, (nid, head, body, color) in enumerate(cards):
        cy = y + i * 118
        n.append(rect(nid, 60, cy, 700, 100, fill=CARD, stroke=OUTLINE, arc=10))
        n.append(txt(nid + "h", head, 80, cy + 8, 660, 26, 15, color, bold=True, mono=False))
        n.append(txt(nid + "b", body, 80, cy + 34, 660, 62, 13, TEXT, valign="top"))
        if i < len(cards) - 1:
            n.append(txt(nid + "a", "↓", 60, cy + 100, 700, 18, 16, MUTED, align="center"))
    n.append(txt("foot", "Data browser: communication.crewradio.online = 3 · .talking = [\"Arabella\"]", 60, H - 52, 740, 24, 13, MUTED))
    diagram("announcement", n, background=BG)


# ---------------------------------------------------------------- 2. how it fits (light)

def how_it_fits():
    box = lambda nid, label, x, y, w, h, fill, stroke, size=14, bold=False: rect(nid, x, y, w, h, fill=fill, stroke=stroke, arc=10, label=label, color=INK, size=size, mono=False, bold=bold)
    edge = "edgeStyle=orthogonalEdgeStyle;rounded=1;html=1;endArrow=block;endFill=1;" + SANS + "fontSize=12;strokeColor=#455A64;strokeWidth=2;"
    n = [rect("bg", 0, 0, W, H, fill=LIGHT_BG, stroke="none", arc=0)]
    n.append(txt("title", "How signalk-crewradio fits in", 60, 40, 900, 44, 30, INK, bold=True, mono=False))
    n.append(txt("sub", "The Signal K server is one more node on the crew's push-to-talk channel, and a speaker for its voice assistant.", 60, 86, 1000, 30, 16, INK_MUTED, mono=False))
    # server container
    n.append(rect("srv", 60, 150, 760, 560, fill=LIGHT_CARD, stroke=LIGHT_LINE, arc=12))
    n.append(txt("srvt", "Signal K server (Raspberry Pi, Node 24)", 84, 160, 600, 30, 16, INK_MUTED, bold=True, mono=False))
    n.append(box("src", "Alarms and data\nanchor · MOB · AIS CPA · engine · depth", 90, 210, 300, 80, "#FFF3E0", "#EF6C00"))
    n.append(box("nodered", "Node-RED, dashboards, other plugins\nPUT voice.say · REST /api/say", 90, 330, 300, 80, "#F3E5F5", "#7B1FA2"))
    n.append(box("cr", "signalk-crewradio\nnotification bridge · Wyoming satellite\nchannel node · roster to Signal K", 450, 210, 340, 100, "#E0F7FA", "#00838F", 14, True))
    n.append(box("wy", "signalk-wyoming\nsay() · queue · urgent priority", 450, 345, 340, 80, "#E8F5E9", "#2E7D32"))
    n.append(box("piper", "signalk-piper (Wyoming)\ntext-to-speech, 22 050 Hz", 450, 460, 340, 70, "#E8F5E9", "#2E7D32"))
    n.append(box("paths", "communication.crewradio.online / .nodes / .talking", 90, 590, 700, 50, "#ECEFF1", "#455A64", 13))
    # phones
    for i, (name, x, y) in enumerate([("Skipper's phone", 900, 190), ("Mate's phone", 1060, 330), ("Deck phone", 900, 470)]):
        n.append(box(f"p{i}", name + "\nCrew Radio", x, y, 150, 80, "#E1F5FE", "#0288D1", 14, True))
    n.append(txt("wlan", "boat WLAN: multicast and broadcast, AES-256-GCM", 860, 600, 380, 24, 12, INK_MUTED, mono=False, align="center"))
    n.append(txt("mesh", "the phones relay on over Bluetooth and Wi-Fi Aware (dashed)", 860, 626, 380, 24, 12, INK_MUTED, mono=False, align="center"))
    e = [
        ("src", "cr", "notifications.*", edge),
        ("nodered", "wy", "say()", edge),
        ("cr", "wy", "say(urgent)", edge),
        ("wy", "piper", "synthesize ↕ audio", edge + "startArrow=block;startFill=1;"),
        ("wy", "cr", "audio-start/chunk/stop", edge),
        ("cr", "paths", "roster", edge),
        ("cr", "p0", "16 kHz PCM, hellos", edge),
        ("cr", "p1", "", edge, [(840, 260), (840, 370)]),
        ("cr", "p2", "", edge, [(840, 260), (840, 510)]),
        ("p0", "p1", "", edge + "dashed=1;"),
        ("p1", "p2", "", edge + "dashed=1;"),
    ]
    diagram("how-it-fits", n, e, background=LIGHT_BG)


# ---------------------------------------------------------------- 3. settings (the admin UI's Plugin Config page)

# Signal K server admin UI (CoreUI): dark sidebar, white navbar, grey page, white cards, RJSF form.
NAV_BG, SIDE_BG, SIDE_TEXT, SIDE_ACTIVE, PAGE_BG = "#FFFFFF", "#2F353A", "#E4E7EA", "#20A8D8", "#E4E5E6"
CARD_BORDER, INK2, DESC, INPUT_BORDER, PRIMARY = "#C8CED3", "#23282C", "#73818F", "#E4E7EA", "#20A8D8"


def settings():
    n = [rect("bg", 0, 0, W, H, fill=PAGE_BG, stroke="none", arc=0)]
    # navbar
    n.append(rect("nav", 0, 0, W, 55, fill=NAV_BG, stroke="none", arc=0))
    n.append(rect("navline", 0, 55, W, 1, fill=CARD_BORDER, stroke="none", arc=0))
    n.append(ellipse("logo", 214, 12, 30, 30, PRIMARY, "none", label="K", color="#FFFFFF", size=15))
    n.append(txt("brand", "Signal K", 252, 12, 200, 30, 20, INK2, bold=True, mono=False))
    n.append(txt("navr", "Logout", W - 110, 12, 90, 30, 14, INK2, mono=False, align="right"))
    # sidebar
    n.append(rect("side", 0, 56, 200, H - 56, fill=SIDE_BG, stroke="none", arc=0))
    items = [("Dashboard", 0), ("Webapps", 0), ("Data Browser", 0), ("Server", 0), ("Plugin Config", 2), ("Server Settings", 1),
             ("Data Connections", 1), ("Backup/Restore", 1), ("Update", 1), ("Server Log", 1), ("Security", 0), ("Documentation", 0), ("Appstore", 0)]
    y = 72
    for i, (label, kind) in enumerate(items):
        if kind == 2:
            n.append(rect(f"sa{i}", 0, y - 6, 200, 36, fill=SIDE_ACTIVE, stroke="none", arc=0))
        n.append(txt(f"s{i}", label, 18 + (18 if kind else 0), y, 180, 24, 14, "#FFFFFF" if kind == 2 else SIDE_TEXT, mono=False))
        y += 40 if kind != 1 else 36
    # breadcrumb
    n.append(txt("crumb", "Home  /  Server  /  Plugin Config", 224, 66, 600, 24, 13, DESC, mono=False))
    # search + card
    n.append(rect("search", 224, 100, 1030, 36, fill="#FFFFFF", stroke=INPUT_BORDER, arc=4, label="Search plugins", color=DESC, size=13, mono=False))
    n.append(rect("card", 224, 152, 1030, H - 152, fill="#FFFFFF", stroke=CARD_BORDER, arc=4))   # runs off the bottom, as a scrolled page does
    n.append(rect("cardh", 224, 152, 1030, 48, fill="#F0F3F5", stroke=CARD_BORDER, arc=4))
    n.append(txt("ct", "Crew Radio", 244, 160, 400, 32, 17, INK2, bold=True, mono=False))
    n.append(txt("cv", "signalk-crewradio  v0.1.0", 640, 160, 400, 32, 13, DESC, mono=False))
    n.append(txt("cs", "3 online · assistant connected", 1000, 160, 240, 32, 13, "#4DBD74", mono=False, align="right"))
    # checkboxes row
    def checkbox(nid, x, y, label, on):
        c = [rect(nid + "b", x, y + 4, 16, 16, fill=(PRIMARY if on else "#FFFFFF"), stroke=(PRIMARY if on else "#8F9BA6"), arc=15, width=1,
                  label=("✓" if on else ""), color="#FFFFFF", size=12, mono=False, bold=True)]
        c.append(txt(nid + "l", label, x + 24, y, 260, 24, 14, INK2, mono=False))
        return c
    n += checkbox("en", 244, 214, "Enabled", True)
    n += checkbox("dbg", 400, 214, "Enable debug log", False)
    n += checkbox("dl", 600, 214, "Enable logging", False)
    # RJSF fields: label above, full-width input, description below
    fields = [
        ("Channel key*", "••••••••••••••", "The crew's channel key, exactly as on the phones (Settings › Channel key). Keeps the channel private; every node must share it."),
        ("Name on the roster", "Arabella", "How the phones list the server. Empty: the vessel's name (Arabella)."),
        ("Multicast group", "239.255.42.1", "Must match the phones' WLAN setting."),
        ("UDP port", "47474", ""),
        ("Network interface", "wlan0", "Interface on the boat WLAN (e.g. wlan0). auto: a wlan interface, else eth/en, else the first with an IPv4 address."),
        ("Hop budget", "4", "How far phones may relay the server's packets over Bluetooth and Wi-Fi Aware."),
    ]
    # The rest of the form (satellite port and bind, chime, gap, the notification section) sits below the fold.
    _below_the_fold = [
        ("Wyoming satellite port", "10701", "Add a satellite in signalk-wyoming with host 127.0.0.1 and this port, id \"crewradio\", and no wake words (speaker only)."),
    ]
    y = 254
    for i, (label, value, desc) in enumerate(fields):
        n.append(txt(f"fl{i}", label, 244, y, 800, 22, 14, INK2, bold=True, mono=False))
        n.append(rect(f"fi{i}", 244, y + 26, 990, 34, fill="#FFFFFF", stroke=INPUT_BORDER, arc=4, label="", color=INK2))
        n.append(txt(f"fv{i}", value, 256, y + 26, 900, 34, 14, INK2, mono=False))
        if desc:
            n.append(txt(f"fd{i}", desc, 244, y + 62, 990, 20, 12, DESC, mono=False))
            y += 92
        else:
            y += 74
    # the page continues below the fold, as a real one does
    diagram("settings", n, background=PAGE_BG)


# ---------------------------------------------------------------- 4. icon

def icon():
    n = [rect("bg", 0, 0, 128, 128, fill=BG, stroke="none", arc=22)]
    n.append(ellipse("ring", 14, 14, 100, 100, CYAN, "#2C363C", 6))
    n.append(txt("t", "CR", 14, 22, 100, 84, 46, "#00343A", bold=True, align="center", mono=False))
    diagram("icon", n, width=128, height=128, background=BG)


if __name__ == "__main__":
    announcement()
    how_it_fits()
    settings()
    icon()
