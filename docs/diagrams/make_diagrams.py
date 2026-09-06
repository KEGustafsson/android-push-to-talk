"""Generates the draw.io diagrams and the screen mock-ups under docs/diagrams from a small
description, so they can be re-made after a change without hand-editing XML. Export to PNG
with draw.io desktop:

    "C:/Program Files/draw.io/draw.io.exe" -x -f png -s 1.5 -b 16 -o docs/images/<name>.png docs/diagrams/<name>.drawio

(scale 1 for the screen mock-ups, 1.5 for the flowcharts; the markdown gives each image a display width)

Run:  python docs/diagrams/make_diagrams.py

The screens are drawn, not photographed: they show the app as it looks with example names,
and carry nobody's device name.
"""
import html
import os

HERE = os.path.dirname(os.path.abspath(__file__))
NL = "\n"

# Palette for the flowcharts: printed on white.
BOX = "rounded=1;whiteSpace=wrap;html=1;fontFamily=Helvetica;fontSize=12;"
UI = BOX + "fillColor=#E1F5FE;strokeColor=#0288D1;"
SVC = BOX + "fillColor=#E8F5E9;strokeColor=#2E7D32;"
ENGINE = BOX + "fillColor=#FFF8E1;strokeColor=#F9A825;"
NET = BOX + "fillColor=#F3E5F5;strokeColor=#7B1FA2;"
AUDIO = BOX + "fillColor=#FBE9E7;strokeColor=#D84315;"
NOTE = "text;html=1;align=left;verticalAlign=top;whiteSpace=wrap;fontFamily=Helvetica;fontSize=11;fontColor=#555555;"
TITLE = NOTE + "fontSize=16;fontStyle=1;fontColor=#000000;"
DECISION = "rhombus;whiteSpace=wrap;html=1;fontFamily=Helvetica;fontSize=11;fillColor=#FFFDE7;strokeColor=#F9A825;"
STEP = BOX + "fillColor=#FFFFFF;strokeColor=#455A64;"
END = BOX + "fillColor=#ECEFF1;strokeColor=#455A64;"
START = "ellipse;whiteSpace=wrap;html=1;fontFamily=Helvetica;fontSize=12;fillColor=#CFD8DC;strokeColor=#455A64;"
PHONE = BOX + "fillColor=#E1F5FE;strokeColor=#0288D1;fontSize=13;fontStyle=1;"
EDGE = "edgeStyle=orthogonalEdgeStyle;rounded=1;html=1;endArrow=block;endFill=1;fontFamily=Helvetica;fontSize=11;strokeColor=#455A64;"
EDGE_DASH = EDGE + "dashed=1;"
EDGE_BI = EDGE + "startArrow=block;startFill=1;"

# Palette for the screens: the app's own dark theme.
BG = "#0F1418"
CARD = "#151B20"
OUTLINE = "#2C363C"
CYAN = "#4DD0E1"
CYAN_DIM = "#123A40"
TEXT = "#E6EEF0"
MUTED = "#8A9AA0"
ON_AIR = "#93000A"
ON_AIR_RING = "#F8BBD0"
TALKING = "#81C784"
MONO = "fontFamily=Courier New;"


def diagram(name, title, nodes, edges, width=1100, height=700, background=None):
    """nodes: (id, label, x, y, w, h, style); edges: (src, dst, label, style, [points])."""
    cells = ['<mxCell id="0"/>', '<mxCell id="1" parent="0"/>']
    for nid, label, x, y, w, h, style in nodes:
        cells.append(
            f'<mxCell id="{nid}" value="{html.escape(label, quote=True)}" style="{style}" vertex="1" parent="1">'
            f'<mxGeometry x="{x}" y="{y}" width="{w}" height="{h}" as="geometry"/></mxCell>'
        )
    for i, e in enumerate(edges):
        src, dst, label, style = e[0], e[1], e[2], e[3]
        points = e[4] if len(e) > 4 else []
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
        f'<diagram id="{name}" name="{html.escape(title, quote=True)}">'
        f'<mxGraphModel dx="1" dy="1" grid="1" gridSize="10" guides="1" tooltips="1" connect="1" arrows="1" '
        f'fold="1" page="1" pageScale="1" pageWidth="{width}" pageHeight="{height}" math="0" shadow="0"{bg}>'
        "<root>" + "".join(cells) + "</root></mxGraphModel></diagram></mxfile>"
    )
    with open(os.path.join(HERE, name + ".drawio"), "w", encoding="utf-8") as f:
        f.write(xml)
    print("wrote", name + ".drawio")


# ================================================================ screens (mock-ups)

def txt(nid, s, x, y, w, h, size=12, color=TEXT, bold=False, align="left", mono=True, spacing=0):
    st = (f"text;html=1;whiteSpace=wrap;align={align};verticalAlign=middle;fontSize={size};fontColor={color};"
          + (MONO if mono else "fontFamily=Helvetica;") + ("fontStyle=1;" if bold else "") + "spacing=0;")
    return (nid, s, x, y, w, h, st)


def rect(nid, x, y, w, h, fill=CARD, stroke=OUTLINE, arc=12, width=1, label="", color=TEXT, size=12):
    st = (f"rounded=1;arcSize={arc};whiteSpace=wrap;html=1;fillColor={fill};strokeColor={stroke};strokeWidth={width};"
          f"fontSize={size};fontColor={color};" + MONO)
    return (nid, label, x, y, w, h, st)


def frame(nid, x, y, w=360, h=760):
    return (nid, "", x, y, w, h, f"rounded=1;arcSize=8;whiteSpace=wrap;html=1;fillColor={BG};strokeColor=#3A444A;strokeWidth=2;")


def toggle(nid, x, y, on):
    track = rect(nid + "t", x, y, 56, 28, fill=(CYAN if on else "#3A444A"), stroke=(CYAN if on else "#3A444A"), arc=50)
    kx = x + 30 if on else x + 4
    knob = (nid + "k", "", kx, y + 4, 20, 20,
            f"ellipse;whiteSpace=wrap;html=1;fillColor={'#0B3A40' if on else '#8A9AA0'};strokeColor=none;")
    return [track, knob]


def main_screen(p, x, y, state, peers="2", peer_row=True, bt=True):
    """state: 'off' | 'on' | 'air'."""
    on = state != "off"
    n = []
    n.append(frame(p + "f", x, y))
    n.append(txt(p + "h1", "CHANNEL", x + 24, y + 40, 200, 20, 11, MUTED, spacing=2))
    n.append(txt(p + "h2", "CREW RADIO", x + 22, y + 60, 220, 40, 26, CYAN, bold=True, mono=False))
    n.append(rect(p + "hc", x + 232, y + 52, 66, 40, arc=25, label=("▮▮ " + (peers if on else "0")), color=(TALKING if state == "air" else TEXT), size=14))
    n.append(txt(p + "m", "⋮", x + 316, y + 52, 30, 40, 22, TEXT, align="center"))
    tiles = [("WLAN", False), ("BLUETOOTH", bt), ("AWARE", True)]
    for i, (name, active) in enumerate(tiles):
        tx = x + 20 + i * 108
        n.append(rect(p + f"t{i}", tx, y + 118, 100, 66, fill=(CYAN_DIM if active else BG), stroke=(CYAN if active else OUTLINE), arc=18,
                      label=name, color=(CYAN if active else MUTED), size=11))
    row_y = y + 202
    if peer_row and bt:
        n.append(rect(p + "pr", x + 20, row_y, 320, 44, label="PEER · SKIPPER'S PHONE", color=MUTED, size=12))
        row_y += 58
    n.append(rect(p + "sw", x + 20, row_y, 320, 50))
    n.append(txt(p + "swl", "ON CHANNEL" if on else "OFF · TAP TO JOIN", x + 36, row_y + 8, 200, 34, 13, (CYAN if on else MUTED), spacing=1))
    n += toggle(p + "sw", x + 276, row_y + 11, on)
    # Playback volume row: mute glyph, slider, number.
    vol_y = row_y + 64
    n.append(rect(p + "vr", x + 20, vol_y, 320, 50))
    n.append(txt(p + "vi", "🔊", x + 30, vol_y + 10, 30, 30, 16, TEXT, align="center", mono=False))
    n.append(rect(p + "vt", x + 68, vol_y + 22, 210, 6, fill="#3A444A", stroke="#3A444A", arc=50))
    n.append(rect(p + "vf", x + 68, vol_y + 22, 147, 6, fill=CYAN, stroke=CYAN, arc=50))
    n.append((p + "vk", "", x + 205, vol_y + 15, 20, 20, f"ellipse;whiteSpace=wrap;html=1;fillColor={CYAN};strokeColor=none;"))
    n.append(txt(p + "vv", "70", x + 286, vol_y + 12, 40, 26, 14, TEXT, bold=True, align="right"))
    if state == "air":
        n.append(txt(p + "tk", "● MATE TALKING", x + 24, vol_y + 58, 300, 20, 11, TALKING, spacing=2))
    cx, cy, r = x + 180, y + 585, 135
    if state == "air":
        n.append((p + "d", "", cx - r, cy - r, 2 * r, 2 * r, f"ellipse;whiteSpace=wrap;html=1;fillColor={ON_AIR};strokeColor={ON_AIR_RING};strokeWidth=10;"))
        n.append(txt(p + "d1", "ON AIR", cx - r, cy - 40, 2 * r, 50, 40, "#FFDAD6", bold=True, align="center", mono=False))
        n.append(txt(p + "d2", "RELEASE TO LISTEN", cx - r, cy + 12, 2 * r, 24, 12, "#FFB4AB", align="center", spacing=2))
    else:
        n.append((p + "d", "", cx - r, cy - r, 2 * r, 2 * r, f"ellipse;whiteSpace=wrap;html=1;fillColor={CYAN};strokeColor=#2C363C;strokeWidth=10;"))
        n.append(txt(p + "d1", "TALK", cx - r, cy - 40, 2 * r, 50, 44, "#00343A", bold=True, align="center", mono=False))
        n.append(txt(p + "d2", "HOLD", cx - r, cy + 12, 2 * r, 24, 13, "#00494F", align="center", spacing=3))
    return n


def status_screen(p, x, y):
    n = [frame(p + "f", x, y)]
    n.append(rect(p + "bar", x + 2, y + 2, 356, 56, fill="#161C21", stroke="#161C21", arc=0))
    n.append(txt(p + "back", "←", x + 18, y + 12, 30, 36, 20, TEXT, align="center"))
    n.append(txt(p + "ttl", "Status", x + 56, y + 12, 200, 36, 20, TEXT, mono=False))
    n.append(txt(p + "h1", "CHANNEL", x + 24, y + 78, 200, 18, 10, MUTED, spacing=2))
    n.append(txt(p + "h2", "CREW RADIO", x + 22, y + 94, 220, 34, 22, CYAN, bold=True, mono=False))
    n.append(rect(p + "oc", x + 236, y + 84, 104, 40, arc=25, label="ON CHANNEL", color=CYAN, size=10))
    # crew card
    n.append(rect(p + "c1", x + 20, y + 146, 320, 150, arc=10))
    n.append(txt(p + "c1t", "CREW", x + 36, y + 156, 100, 20, 11, CYAN, spacing=2))
    n.append(txt(p + "c1r", "2 ABOARD", x + 220, y + 156, 106, 20, 11, MUTED, align="right", spacing=2))
    n.append((p + "dot1", "", x + 36, y + 190, 10, 10, f"ellipse;fillColor={TALKING};strokeColor=none;"))
    n.append(txt(p + "n1", "Mate", x + 56, y + 182, 150, 26, 15, TEXT, mono=False))
    n.append(txt(p + "n1s", "TALKING", x + 236, y + 184, 90, 22, 11, TALKING, align="right", spacing=1))
    n.append(txt(p + "n1d", "on BT+Aware · id 7a91c2e0 · heard just now", x + 56, y + 206, 280, 18, 10, MUTED, mono=False))
    n.append((p + "dot2", "", x + 36, y + 240, 10, 10, f"ellipse;fillColor={TALKING};strokeColor=none;"))
    n.append(txt(p + "n2", "Skipper's phone", x + 56, y + 232, 170, 26, 15, TEXT, mono=False))
    n.append(txt(p + "n2s", "AWARE · 1 HOP", x + 216, y + 234, 110, 22, 11, MUTED, align="right", spacing=1))
    n.append(txt(p + "n2d", "on BT · id cfe7198c · heard just now", x + 56, y + 256, 280, 18, 10, MUTED, mono=False))
    # this phone card
    n.append(rect(p + "c2", x + 20, y + 312, 320, 236, arc=10))
    n.append(txt(p + "c2t", "THIS PHONE", x + 36, y + 322, 140, 20, 11, CYAN, spacing=2))
    n.append(txt(p + "c2r", "428deaea", x + 220, y + 322, 106, 20, 11, MUTED, align="right", spacing=2))
    rows = [("MY NAME", "Deckhand"), ("MODE", "Half duplex"), ("RELAY", "On"), ("CODEC", "Opus"),
            ("AUDIO", "Headset · Jabra"), ("HOP LIMIT", "4"), ("VERSION", "1.62 · 5f3e0ee")]
    for i, (k, v) in enumerate(rows):
        ry = y + 350 + i * 27
        n.append(txt(p + f"k{i}", k, x + 36, ry, 120, 22, 11, MUTED, spacing=1))
        n.append(txt(p + f"v{i}", v, x + 150, ry, 176, 22, 13, TEXT, align="right", mono=False))
    # network card
    n.append(rect(p + "c3", x + 20, y + 564, 320, 176, arc=10))
    n.append(txt(p + "c3t", "NETWORK", x + 36, y + 574, 140, 20, 11, CYAN, spacing=2))
    n.append(txt(p + "c3r", "BT + AWARE", x + 200, y + 574, 126, 20, 11, MUTED, align="right", spacing=2))
    rows = [("WLAN0", "192.168.0.35/24"), ("AWARE_DATA0", "fe80::cb:b7ff:fe6c:de48"), ("MULTICAST", "239.255.42.1:47474"), ("BLUETOOTH", "Mate's phone")]
    for i, (k, v) in enumerate(rows):
        ry = y + 602 + i * 30
        n.append(txt(p + f"nk{i}", k, x + 36, ry, 120, 22, 11, MUTED, spacing=1))
        n.append(txt(p + f"nv{i}", v, x + 130, ry, 196, 22, 12, TEXT, align="right", mono=False))
    return n


def settings_screen(p, x, y):
    n = [frame(p + "f", x, y, h=940)]
    n.append(rect(p + "bar", x + 2, y + 2, 356, 56, fill="#161C21", stroke="#161C21", arc=0))
    n.append(txt(p + "back", "←", x + 18, y + 12, 30, 36, 20, TEXT, align="center"))
    n.append(txt(p + "ttl", "Settings", x + 56, y + 12, 200, 36, 20, TEXT, mono=False))
    items = [
        ("cat", "Me", None), ("row", "My name", "Deckhand"),
        ("cat", "Channel", None), ("row", "Channel name", "CREW RADIO"), ("row", "Channel key", "q7wk-m3xv-pd2h"),
        ("cat", "Talking", None),
        ("sw", "Full duplex", "Off: hold to talk, others muted meanwhile. On: tap to toggle, everyone heard at once.", False),
        ("row", "Talk button", "Headset button and volume keys"),
        ("row", "Audio output", "Headset when connected; else earpiece at the ear, loudspeaker otherwise"),
        ("sw", "Voice keys the mic", "With a Bluetooth headset: just speak.", False),
        ("sw", "Talk key tones", "One tone in the ear when the mic keys, two when it un-keys.", False),
        ("sw", "Keep screen on", "While on the channel.", True),
        ("sw", "Relay", "Forward what this phone hears to its other links.", True),
        ("sw", "Opus compression", "About a tenth of the bandwidth of raw audio.", True),
    ]
    cy = y + 72
    for i, it in enumerate(items):
        kind = it[0]
        if kind == "cat":
            n.append(txt(p + f"i{i}", it[1], x + 60, cy, 200, 20, 12, CYAN, mono=False))
            cy += 30
        elif kind == "row":
            lines = 1 + len(it[2]) // 44
            n.append(txt(p + f"i{i}", it[1], x + 60, cy, 260, 22, 15, TEXT, mono=False))
            n.append(txt(p + f"s{i}", it[2], x + 60, cy + 22, 260, 16 * lines + 4, 11, MUTED, mono=False))
            cy += 34 + 16 * lines + 8
        else:
            lines = 1 + len(it[2]) // 40
            n.append(txt(p + f"i{i}", it[1], x + 60, cy, 220, 22, 15, TEXT, mono=False))
            n.append(txt(p + f"s{i}", it[2], x + 60, cy + 22, 220, 15 * lines + 4, 10, MUTED, mono=False))
            n += toggle(p + f"g{i}", x + 288, cy + 8 + 7 * lines, it[3])
            cy += 34 + 15 * lines + 8
    return n


diagram("screens", "The screens", nodes=(
    [txt("t0", "Off the channel", 40, 20, 360, 30, 14, "#000000", bold=True, align="center", mono=False)]
    + main_screen("a", 40, 60, "off", bt=True)
    + [txt("t1", "On the channel", 440, 20, 360, 30, 14, "#000000", bold=True, align="center", mono=False)]
    + main_screen("b", 440, 60, "on")
    + [txt("t2", "Talking", 840, 20, 360, 30, 14, "#000000", bold=True, align="center", mono=False)]
    + main_screen("c", 840, 60, "air")
), edges=[], width=1240, height=860)

diagram("screen-main", "Main screen", nodes=main_screen("a", 20, 20, "on"), edges=[], width=400, height=800)
diagram("screen-on-air", "On air", nodes=main_screen("a", 20, 20, "air"), edges=[], width=400, height=800)
diagram("screen-status", "Status screen", nodes=status_screen("s", 20, 20), edges=[], width=400, height=800)
diagram("screen-settings", "Settings screen", nodes=settings_screen("s", 20, 20), edges=[], width=400, height=980)


# ================================================================ flowcharts

# ---------------------------------------------------------------- 1. architecture
diagram("architecture", "How the app is built", nodes=[
    ("t", "Crew Radio - what runs where", 40, 20, 500, 30, TITLE),
    ("ui", "Main screen" + NL + "(MainActivity)", 40, 80, 170, 70, UI),
    ("st", "Status screen", 40, 170, 170, 50, UI),
    ("se", "Settings screen", 40, 240, 170, 50, UI),
    ("svc", "Background service (PttService)" + NL + "keeps the channel alive with the screen off," + NL
     + "holds the notification, the talk keys," + NL + "the wake lock and the Wi-Fi lock", 280, 80, 300, 110, SVC),
    ("eng", "Engine (PttEngine)" + NL + "roster of the crew, relay, packet order," + NL + "who is talking, the voice gate",
     280, 230, 300, 90, ENGINE),
    ("cap", "Microphone" + NL + "(AudioCapture)" + NL + "16 kHz, 20 ms frames", 40, 400, 170, 70, AUDIO),
    ("enc", "Opus encoder" + NL + "(platform codec)", 40, 500, 170, 60, AUDIO),
    ("pk", "Packet" + NL + "14-byte header +" + NL + "Opus/PCM frame, or a hello" + NL + "(roster heartbeat)", 280, 460, 170, 80, END),
    ("lan", "WLAN" + NL + "UDP multicast + broadcast", 530, 400, 160, 60, NET),
    ("bt", "Bluetooth" + NL + "RFCOMM link to one peer", 530, 480, 160, 60, NET),
    ("aw", "Wi-Fi Aware" + NL + "phone-to-phone, no router", 530, 560, 160, 60, NET),
    ("dec", "Opus decoder" + NL + "per talker", 800, 400, 190, 50, AUDIO),
    ("mix", "Mixer" + NL + "one queue per talker," + NL + "loss concealment, cue tones", 800, 480, 190, 70, AUDIO),
    ("play", "Speaker, earpiece or headset" + NL + "(AudioPlayback + AudioRoute)", 800, 580, 190, 60, AUDIO),
    ("n1", "Every phone is the same: there is no server and no master. Each phone sends its own frames on every"
     + NL + "transport it has on and, with Relay on (the default) and hops remaining, forwards what it hears to its"
     + NL + "other transports, so a phone that has both Wi-Fi Aware and Bluetooth bridges the two.", 40, 660, 900, 60, NOTE),
], edges=[
    ("ui", "svc", "binds while visible", EDGE),
    ("st", "eng", "polls once a second", EDGE_DASH + "exitX=1;exitY=0.5;entryX=0;entryY=0.3;"),
    ("se", "eng", "settings pushed live", EDGE_DASH + "exitX=1;exitY=0.5;entryX=0;entryY=0.7;"),
    ("svc", "eng", "", EDGE),
    ("eng", "cap", "keys the mic", EDGE + "exitX=0.15;exitY=1;entryX=0.5;entryY=0;"),
    ("cap", "enc", "", EDGE),
    ("enc", "pk", "", EDGE),
    ("pk", "lan", "", EDGE + "exitX=1;exitY=0.3;entryX=0;entryY=0.5;"),
    ("pk", "bt", "", EDGE + "exitX=1;exitY=0.5;entryX=0;entryY=0.5;"),
    ("pk", "aw", "", EDGE + "exitX=1;exitY=0.7;entryX=0;entryY=0.5;"),
    ("lan", "eng", "", EDGE_DASH + "exitX=0.5;exitY=0;entryX=0.85;entryY=1;"),
    ("bt", "eng", "received" + NL + "packets", EDGE_DASH + "exitX=1;exitY=0.5;entryX=1;entryY=0.7;", [(740, 510), (740, 293)]),
    ("aw", "eng", "", EDGE_DASH + "exitX=1;exitY=0.5;entryX=1;entryY=0.85;", [(720, 590), (720, 306)]),
    ("eng", "dec", "decode and play", EDGE + "exitX=1;exitY=0.3;entryX=0.5;entryY=0;"),
    ("dec", "mix", "", EDGE),
    ("mix", "play", "", EDGE),
], width=1020, height=740)

# ---------------------------------------------------------------- 2. packet flow (receive path)
diagram("packet-flow", "What happens to a received packet", nodes=[
    ("t", "A packet arrives on any transport", 40, 20, 500, 30, TITLE),
    ("s", "packet", 40, 70, 100, 50, START),
    ("d1", "our own?", 200, 60, 120, 70, DECISION),
    ("d2", "seen before?" + NL + "(kind, sender, number)", 370, 60, 140, 70, DECISION),
    ("r", "relay: forward to every other transport" + NL + "and link, hop count minus one" + NL + "(if relay is on and hops remain)",
     560, 60, 260, 70, STEP),
    ("drop", "drop", 210, 170, 100, 50, END),
    ("drop2", "drop: a duplicate" + NL + "via another path", 380, 170, 120, 60, END),
    ("d3", "a hello?", 370, 280, 140, 70, DECISION),
    ("ro", "update the roster:" + NL + "name, transports, hops, seen now", 560, 280, 260, 70, STEP),
    ("ta", "mark the sender as talking", 370, 400, 140, 60, STEP),
    ("d4", "half duplex and" + NL + "we are transmitting?", 370, 500, 140, 80, DECISION),
    ("ign", "not played" + NL + "(radio semantics)", 200, 510, 120, 60, END),
    ("gap", "gap in the sender's numbers?" + NL + "reserve up to 3 slots to conceal", 560, 505, 260, 70, STEP),
    ("dec", "decode (Opus) or take raw PCM", 560, 605, 260, 50, STEP),
    ("mix", "mixer: queue per talker, summed," + NL + "missing slots faded from the last frame", 560, 685, 260, 60, STEP),
    ("out", "speaker / earpiece / headset", 560, 775, 260, 50, END),
], edges=[
    ("s", "d1", "", EDGE),
    ("d1", "d2", "no", EDGE),
    ("d1", "drop", "yes", EDGE),
    ("d2", "r", "no", EDGE),
    ("d2", "drop2", "yes", EDGE),
    ("r", "d3", "", EDGE, [(690, 250), (440, 250)]),
    ("d3", "ro", "yes", EDGE),
    ("d3", "ta", "no", EDGE),
    ("ta", "d4", "", EDGE),
    ("d4", "ign", "yes", EDGE),
    ("d4", "gap", "no", EDGE),
    ("gap", "dec", "", EDGE),
    ("dec", "mix", "", EDGE),
    ("mix", "out", "", EDGE),
], width=880, height=860)

# ---------------------------------------------------------------- 3. talk keys
diagram("talk-keys", "Ways to key the mic", nodes=[
    ("t", "How the mic gets keyed", 40, 20, 500, 30, TITLE),
    ("b", "Big button on screen", 40, 80, 180, 50, UI),
    ("v", "Phone volume keys" + NL + "(screen on or off)", 40, 150, 180, 60, UI),
    ("h", "Headset / media button" + NL + "(wired, or Bluetooth when it" + NL + "reaches the app)", 40, 230, 180, 70, UI),
    ("vox", "Your voice" + NL + "(Voice keys the mic)", 40, 320, 180, 60, UI),
    ("hd", "Half duplex: hold to talk" + NL + "Full duplex: tap to toggle", 300, 80, 220, 50, STEP),
    ("vt", "press = on, next press = off" + NL + "(a held key counts once)", 300, 150, 220, 60, STEP),
    ("ht", "click = on / off" + NL + "hold = talk while held", 300, 230, 220, 70, STEP),
    ("g", "speech opens the gate," + NL + "1.5 s of quiet closes it", 300, 320, 220, 60, STEP),
    ("d", "with a Bluetooth headset:" + NL + "only if the setting is on", 560, 300, 200, 50, DECISION + "fontSize=10;"),
    ("d2", "on the phone itself:" + NL + "only while it is at your ear" + NL + "(proximity sensor)", 560, 370, 200, 60, DECISION + "fontSize=10;"),
    ("mic", "Mic on" + NL + "(ON AIR)", 820, 190, 120, 70, END + "fillColor=#FFCDD2;strokeColor=#C62828;fontStyle=1;"),
    ("fb", "Feedback: the disc turns red, a short buzz on the phone," + NL + "optionally one tone in the ear (two when off)."
     + NL + "Half duplex: others are muted while you are on air.", 300, 430, 460, 60, NOTE),
], edges=[
    ("b", "hd", "", EDGE), ("v", "vt", "", EDGE), ("h", "ht", "", EDGE), ("vox", "g", "", EDGE),
    ("hd", "mic", "", EDGE), ("vt", "mic", "", EDGE), ("ht", "mic", "", EDGE),
    ("g", "d", "", EDGE), ("g", "d2", "", EDGE),
    ("d", "mic", "yes", EDGE), ("d2", "mic", "yes", EDGE),
], width=980, height=520)

# ---------------------------------------------------------------- 4. audio route
diagram("audio-route", "Where the voice goes", nodes=[
    ("t", "Where the sound goes (Settings > Talking > Audio output)", 40, 20, 600, 30, TITLE),
    ("s", "on channel", 40, 80, 110, 50, START),
    ("d0", "setting?", 200, 70, 120, 70, DECISION),
    ("sp", "Loudspeaker", 780, 70, 150, 50, END),
    ("d1", "Bluetooth headset" + NL + "connected?", 380, 160, 150, 80, DECISION),
    ("bt", "Bluetooth headset" + NL + "(mic and ear)", 780, 175, 150, 50, END),
    ("d2", "wired / USB" + NL + "headset plugged in?", 380, 270, 150, 80, DECISION),
    ("wh", "Wired headset", 780, 285, 150, 50, END),
    ("d3", "phone at the ear?" + NL + "(proximity sensor)", 380, 380, 150, 80, DECISION),
    ("ep", "Earpiece" + NL + "+ voice keys the mic" + NL + "+ screen dark", 780, 380, 150, 70, END),
    ("sp2", "Loudspeaker" + NL + "+ the button", 780, 470, 150, 50, END),
    ("ep2", "Earpiece always" + NL + "(voice keys the mic at the ear)", 200, 480, 200, 60, END),
    ("n", "With the default setting the route follows changes live: connect a headset and the voice moves to it,"
     + NL + "lift the phone to your ear and it becomes a phone call, put it down and it is a radio again."
     + NL + "The two forced settings ignore headsets and the ear.", 40, 570, 700, 60, NOTE),
], edges=[
    ("s", "d0", "", EDGE),
    ("d0", "sp", "always the loudspeaker", EDGE),
    ("d0", "d1", "default", EDGE, [(260, 200)]),
    ("d0", "ep2", "earpiece", EDGE, [(260, 510)]),
    ("d1", "bt", "yes", EDGE),
    ("d1", "d2", "no", EDGE),
    ("d2", "wh", "yes", EDGE),
    ("d2", "d3", "no", EDGE),
    ("d3", "ep", "yes", EDGE),
    ("d3", "sp2", "no", EDGE, [(455, 495)]),
], width=980, height=640)

# ---------------------------------------------------------------- 5. mesh
diagram("mesh", "A crew of three phones", nodes=[
    ("t", "Example: three phones, two kinds of link, everyone hears everyone", 40, 20, 700, 30, TITLE),
    ("a", "Phone A" + NL + "Wi-Fi Aware + Bluetooth", 60, 120, 200, 70, PHONE),
    ("b", "Phone B" + NL + "Wi-Fi Aware only", 420, 120, 200, 70, PHONE),
    ("c", "Phone C" + NL + "Bluetooth only" + NL + "(no Wi-Fi Aware hardware)", 60, 300, 200, 80, PHONE),
    ("n1", "A hears B over Aware and forwards it to C over Bluetooth (relay). C hears A directly."
     + NL + "B reaches C only through A: on B's Status screen C shows as \"1 hop\"."
     + NL + "Each packet carries a hop count (4 by default) so nothing circulates forever,"
     + NL + "and a seen-list drops copies that arrive by two paths.", 320, 260, 500, 90, NOTE),
    ("n2", "On a boat with a WLAN router, WLAN is the third kind of link; a phone with WLAN and"
     + NL + "Bluetooth on at once bridges them the same way.", 320, 370, 500, 40, NOTE),
], edges=[
    ("a", "b", "Wi-Fi Aware", EDGE_BI + "strokeColor=#7B1FA2;strokeWidth=2;"),
    ("a", "c", "Bluetooth", EDGE_BI + "strokeColor=#0288D1;strokeWidth=2;"),
], width=880, height=440)

# ---------------------------------------------------------------- 6. links overview (the idea)
diagram("links", "One app, every link", nodes=[
    ("t", "One app, every kind of link between the phones", 40, 20, 700, 30, TITLE),
    ("p", "Your phone" + NL + "Crew Radio", 340, 200, 160, 70, PHONE),
    ("l1", "WLAN" + NL + "the boat's router or a hotspot:" + NL + "everyone on the same network", 40, 80, 220, 70, NET),
    ("l2", "Wi-Fi Aware" + NL + "phone to phone, no router," + NL + "Wi-Fi range", 40, 320, 220, 70, NET),
    ("l3", "Bluetooth" + NL + "phone to phone, close range," + NL + "any Android 10+ phone", 580, 80, 220, 70, NET),
    ("l4", "Relay (a setting, on by default, not a link):" + NL + "what this phone hears on one link it repeats on its other links,"
     + NL + "up to the hop limit, so it bridges them", 300, 320, 480, 60, ENGINE + "dashed=1;"),
    ("n", "Tick the links you have (WLAN, Wi-Fi Aware, Bluetooth). Phones find each other, no server, no account, no internet."
     + NL + "Lose one link and the others carry on; a phone in the middle bridges the rest.", 40, 440, 760, 40, NOTE),
], edges=[
    ("p", "l1", "", EDGE_BI + "strokeColor=#7B1FA2;"),
    ("p", "l2", "", EDGE_BI + "strokeColor=#7B1FA2;"),
    ("p", "l3", "", EDGE_BI + "strokeColor=#0288D1;"),
    ("p", "l4", "", EDGE_DASH + "strokeColor=#F9A825;endArrow=none;"),
], width=860, height=520)
