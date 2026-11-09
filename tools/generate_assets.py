#!/usr/bin/env python3
"""Generate original Liquid Glass icon assets and Android appfilter resources."""
from __future__ import annotations
import json, math, xml.etree.ElementTree as ET
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
SIZE = 512
SCALE = 2
CANVAS = SIZE * SCALE

# Original symbolic artwork, mapped to widely used Android package/activity IDs.
ICONS = [
    ("phone", "Phone", "phone handset", [("com.google.android.dialer", ".extensions.GoogleDialtactsActivity"), ("com.android.dialer", ".DialtactsActivity")]),
    ("messages", "Messages", "message bubble", [("com.google.android.apps.messaging", ".ui.ConversationListActivity"), ("com.samsung.android.messaging", ".ui.view.conversation.ConversationComposer")]),
    ("mail", "Mail", "envelope", [("com.android.email", ".activity.Welcome")]),
    ("browser", "Browser", "compass", [("com.android.chrome", "com.google.android.apps.chrome.Main"), ("org.mozilla.firefox", ".App"), ("com.brave.browser", "com.google.android.apps.chrome.Main")]),
    ("camera", "Camera", "camera", [("com.google.android.GoogleCamera", "com.android.camera.CameraLauncher"), ("com.sec.android.app.camera", ".Camera")]),
    ("photos", "Photos", "flower", [("com.google.android.apps.photos", ".home.HomeActivity")]),
    ("maps", "Maps", "map pin", [("com.google.android.apps.maps", "com.google.android.maps.MapsActivity")]),
    ("calendar", "Calendar", "calendar", [("com.google.android.calendar", "com.android.calendar.AllInOneActivity"), ("com.samsung.android.calendar", ".MonthActivity")]),
    ("clock", "Clock", "clock", [("com.google.android.deskclock", "com.android.deskclock.DeskClock"), ("com.sec.android.app.clockpackage", ".ClockPackage")]),
    ("weather", "Weather", "sun cloud", [("com.google.android.apps.weather", ".WeatherActivity"), ("com.sec.android.daemonapp", ".weather.WeatherMain")]),
    ("notes", "Notes", "notepad", [("com.google.android.keep", ".activities.BrowseActivity"), ("com.samsung.android.app.notes", ".기본")]),
    ("reminders", "Reminders", "checklist", [("com.google.android.apps.tasks", ".ui.TaskListActivity"), ("com.samsung.android.app.reminder", ".ui.ReminderMainActivity")]),
    ("files", "Files", "folder", [("com.google.android.documentsui", "com.android.documentsui.files.FilesActivity"), ("com.google.android.apps.nbu.files", ".home.HomeActivity"), ("com.sec.android.app.myfiles", ".external.ui.MainActivity")]),
    ("music", "Music", "music note", [("com.sec.android.app.music", ".common.activity.MusicMainActivity")]),
    ("podcasts", "Podcasts", "podcast", [("com.google.android.apps.podcasts", ".home.HomeActivity")]),
    ("tv", "TV", "television", [("com.google.android.videos", ".tv.TvActivity"), ("com.google.android.youtube.tv", ".MainActivity")]),
    ("app_store", "Store", "storefront", []),
    ("settings", "Settings", "gear", [("com.android.settings", ".Settings"), ("com.android.settings", ".Settings$DevelopmentSettingsDashboardActivity")]),
    ("wallet", "Wallet", "wallet card", [("com.google.android.apps.walletnfcrel", ".ui.main.MainActivity"), ("com.samsung.android.spay", ".ui.SpayMainActivity")]),
    ("health", "Health", "heart", [("com.google.android.apps.fitness", ".GFitMainActivity"), ("com.sec.android.app.shealth", ".SHealthMainActivity")]),
    ("home", "Home", "house", [("com.google.android.apps.chromecast.app", ".HomeActivity"), ("com.samsung.android.oneconnect", ".ui.HomeActivity")]),
    ("contacts", "Contacts", "person", [("com.google.android.contacts", ".activities.PeopleActivity"), ("com.samsung.android.app.contacts", ".activities.ContactUsActivity")]),
    ("calculator", "Calculator", "calculator", [("com.google.android.calculator", "com.android.calculator2.Calculator"), ("com.sec.android.app.popupcalculator", ".Calculator")]),
    ("translate", "Translate", "translation bubbles", [("com.google.android.apps.translate", ".TranslateActivity")]),
    ("shortcuts", "Shortcuts", "shortcut arrows", []),
    ("video_call", "Video Call", "video camera", [("com.google.android.apps.tachyon", ".MainActivity"), ("com.google.android.apps.meetings", ".MainActivity")]),
    ("drive", "Drive", "triangle", [("com.google.android.apps.docs", ".app.NewMainProxyActivity")]),
    ("gmail", "Gmail", "letter M", [("com.google.android.gm", ".ConversationListActivityGmail")]),
    ("youtube", "YouTube", "play button", [("com.google.android.youtube", ".app.honeycomb.Shell$HomeActivity")]),
    ("youtube_music", "YouTube Music", "music play", [("com.google.android.apps.youtube.music", ".activities.MusicActivity")]),
    ("whatsapp", "WhatsApp", "chat phone", [("com.whatsapp", ".Main")]),
    ("instagram", "Instagram", "camera frame", [("com.instagram.android", ".activity.MainTabActivity")]),
    ("tiktok", "TikTok", "music note", [("com.zhiliaoapp.musically", ".splash.SplashActivity"), ("com.ss.android.ugc.trill", ".splash.SplashActivity")]),
    ("spotify", "Spotify", "sound waves", [("com.spotify.music", ".MainActivity")]),
    ("telegram", "Telegram", "paper plane", [("org.telegram.messenger", ".LaunchActivity")]),
    ("discord", "Discord", "game controller", [("com.discord", ".main.MainActivity")]),
    ("netflix", "Netflix", "cinema N", [("com.netflix.mediaclient", ".ui.launch.UIWebViewActivity")]),
    ("outlook", "Outlook", "mail tile", [("com.microsoft.office.outlook", ".MainActivity")]),
    ("reddit", "Reddit", "community bubble", [("com.reddit.frontpage", ".StartActivity")]),
    ("x_social", "X", "cross", [("com.twitter.android", ".StartActivity")]),
    ("chatgpt", "ChatGPT", "orbit flower", [("com.openai.chatgpt", ".MainActivity")]),
    ("play_store", "Play Store", "play triangle", [("com.android.vending", "com.google.android.finsky.activities.MainActivity")]),
]
PALETTE = ["#4C8DFF", "#37B7AE", "#F47776", "#A678F0", "#42B978", "#F6B84A", "#E85D82", "#4EBDDE", "#7388A9", "#FF825A"]

def rgb(h):
    h=h.lstrip('#'); return tuple(int(h[i:i+2],16) for i in (0,2,4))
def mix(a,b,t):
    aa,bb=rgb(a),rgb(b); return tuple(round(x*(1-t)+y*t) for x,y in zip(aa,bb))
def hx(c): return '#%02x%02x%02x' % c

def palette_for(i, dark):
    accent=PALETTE[i%len(PALETTE)]
    if dark:
        return hx(mix(accent, "#1A2639", .58)), hx(mix(accent, "#07101C", .83)), hx(mix(accent, "#F7FAFF", .36)), accent
    return hx(mix(accent, "#FFFFFF", .26)), hx(mix(accent, "#0B1C35", .12)), "#253551", hx(mix(accent,"#12223B",.10))

class Painter:
    def __init__(self, draw): self.d=draw
    def p(self,x,y): return (round(x*SCALE),round(y*SCALE))
    def box(self,x1,y1,x2,y2): return tuple(round(v*SCALE) for v in (x1,y1,x2,y2))
    def line(self, pts, fill, w=15):
        q=[self.p(*p) for p in pts]
        self.d.line(q, fill=fill, width=max(1,round(w*SCALE)), joint="curve")
        r=max(1,round(w*SCALE/2))
        for x,y in q:
            self.d.ellipse((x-r,y-r,x+r,y+r),fill=fill)
    def rect(self, box, fill=None, outline=None, w=14, radius=18):
        self.d.rounded_rectangle(self.box(*box),radius=round(radius*SCALE),fill=fill,outline=outline,width=round(w*SCALE))
    def ellipse(self, box, fill=None, outline=None, w=14):
        self.d.ellipse(self.box(*box),fill=fill,outline=outline,width=round(w*SCALE))
    def poly(self, pts, fill=None, outline=None, w=12):
        self.d.polygon([self.p(*p) for p in pts],fill=fill)
        if outline: self.d.line([self.p(*p) for p in pts+[pts[0]]],fill=outline,width=round(w*SCALE),joint="curve")
    def arc(self, box, start, end, fill, w=14): self.d.arc(self.box(*box),start,end,fill=fill,width=round(w*SCALE))

def bezier(p0,p1,p2,p3,n=24):
    pts=[]
    for i in range(n+1):
        t=i/n; u=1-t
        pts.append((u**3*p0[0]+3*u*u*t*p1[0]+3*u*t*t*p2[0]+t**3*p3[0],u**3*p0[1]+3*u*u*t*p1[1]+3*u*t*t*p2[1]+t**3*p3[1]))
    return pts

def draw_symbol(kind, painter, color, accent):
    p=painter; white=color; a=accent
    # The symbols are original, simplified pictograms; no third-party logo artwork is embedded.
    if kind in ("phone handset", "chat phone"):
        p.line([(183,168),(157,195),(167,244),(205,294),(256,337),(306,348),(334,322)],white,25)
        p.line([(177,173),(208,204)],a,18); p.line([(296,310),(326,340)],a,18)
    elif kind=="message bubble":
        p.rect((142,158,370,326),None,white,15,48); p.poly([(185,320),(174,356),(222,327)],white)
        p.ellipse((197,235,217,255),fill=a); p.ellipse((247,235,267,255),fill=a); p.ellipse((297,235,317,255),fill=a)
    elif kind in ("envelope","mail tile"):
        p.rect((130,174,382,338),None,white,15,28); p.line([(139,191),(256,279),(373,191)],white,15); p.line([(140,324),(224,253)],a,12); p.line([(372,324),(288,253)],a,12)
    elif kind=="compass":
        p.ellipse((142,142,370,370),None,white,15); p.poly([(296,176),(242,281),(337,226)],a); p.poly([(216,337),(270,231),(177,286)],white)
    elif kind=="camera":
        p.rect((130,184,382,345),None,white,16,34); p.rect((178,158,247,194),a,None,0,15); p.ellipse((205,221,305,321),None,white,15); p.ellipse((235,251,275,291),fill=a); p.ellipse((331,206,350,225),fill=a)
    elif kind=="flower":
        for ang in range(0,360,60):
            x=256+59*math.cos(math.radians(ang)); y=256+59*math.sin(math.radians(ang)); p.ellipse((x-40,y-40,x+40,y+40),fill=white)
        p.ellipse((234,234,278,278),fill=a)
    elif kind in ("map pin","house"):
        if kind=="map pin":
            p.poly([(256,359),(176,251),(171,217),(182,181),(209,158),(256,150),(303,158),(330,181),(341,217),(336,251)],white)
            p.ellipse((224,191,288,255),fill=a)
        else:
            p.poly([(143,250),(256,155),(369,250)],None,white,18); p.rect((174,244,338,365),None,white,16,10); p.rect((229,284,283,365),a,None,0,8); p.rect((194,271,221,299),white,None,0,6); p.rect((291,271,318,299),white,None,0,6)
    elif kind=="calendar":
        p.rect((149,153,363,365),None,white,15,27); p.line([(150,213),(362,213)],white,15); p.line([(207,137),(207,178)],a,16); p.line([(305,137),(305,178)],a,16)
        for x,y in [(203,254),(256,254),(309,254),(203,309),(256,309),(309,309)]: p.ellipse((x-6,y-6,x+6,y+6),fill=white)
    elif kind=="clock":
        p.ellipse((145,145,367,367),None,white,16); p.line([(256,184),(256,256),(313,288)],white,17); p.ellipse((244,244,268,268),fill=a)
    elif kind=="sun cloud":
        p.ellipse((190,143,294,247),fill=a)
        p.arc((130,198,350,357),180,360,white,17); p.line([(145,280),(367,280)],white,17); p.arc((130,228,246,355),90,180,white,17); p.arc((264,230,378,354),0,90,white,17)
    elif kind=="notepad":
        p.rect((160,137,350,375),None,white,15,24); p.poly([(294,138),(350,194),(294,194)],a); p.line([(194,234),(314,234)],white,13); p.line([(194,276),(314,276)],white,13); p.line([(194,318),(286,318)],a,13)
    elif kind=="checklist":
        for y in (185,256,327):
            p.rect((140,y-19,179,y+20),None,a,8,10); p.line([(148,y),(157,y+9),(173,y-10)],white,8); p.line([(205,y),(365,y)],white,12)
    elif kind=="folder":
        p.poly([(127,196),(218,196),(244,222),(382,222),(382,354),(127,354)],None,white,15); p.poly([(130,225),(382,225),(354,342),(130,342)],a)
    elif kind in ("music note","letter M","cinema N","cross"):
        if kind=="cinema N": p.poly([(174,356),(174,156),(210,156),(302,303),(302,156),(338,156),(338,356),(302,356),(210,208),(210,356)],white)
        elif kind=="cross": p.line([(173,170),(339,342)],white,27); p.line([(339,170),(173,342)],a,27)
        else:
            p.line([(271,160),(271,300)],white,18); p.line([(271,160),(346,177),(346,201),(271,184)],a,18); p.ellipse((174,287,246,359),fill=white); p.ellipse((295,302,365,372),fill=a)
    elif kind=="podcast":
        p.ellipse((242,235,270,263),fill=a); p.arc((209,203,303,297),210,330,white,13); p.arc((178,172,334,328),210,330,white,13); p.arc((145,140,367,362),210,330,white,13); p.line([(256,269),(256,344)],white,15)
    elif kind=="television":
        p.rect((135,190,377,347),None,white,15,26); p.line([(220,169),(256,138),(292,169)],a,13); p.poly([(235,230),(235,308),(304,269)],white)
    elif kind=="storefront":
        p.poly([(139,223),(167,157),(345,157),(373,223)],None,white,16)
        p.line([(139,223),(373,223)],accent,14)
        p.rect((157,224,355,361),None,white,14,12)
        p.rect((226,277,285,361),accent,None,0,8)
        p.rect((177,258,210,291),white,None,0,6)
        p.rect((301,258,334,291),white,None,0,6)
    elif kind=="stylized A":
        p.line([(164,346),(250,160),(348,346)],white,22); p.line([(198,289),(318,289)],a,17); p.line([(225,230),(289,230)],white,14)
    elif kind=="gear":
        p.ellipse((203,203,309,309),None,white,17); p.ellipse((235,235,277,277),fill=a)
        for ang in range(0,360,45):
            x=256+105*math.cos(math.radians(ang)); y=256+105*math.sin(math.radians(ang)); x2=256+71*math.cos(math.radians(ang)); y2=256+71*math.sin(math.radians(ang)); p.line([(x2,y2),(x,y)],white,20)
    elif kind=="wallet card":
        p.rect((140,177,372,343),None,white,15,26); p.rect((141,218,371,300),a,None,0,12); p.rect((287,238,370,284),white,None,0,12); p.ellipse((310,252,328,270),fill=white)
    elif kind=="heart":
        pts=bezier((256,342),(204,300),(145,253),(153,206))+bezier((153,206),(163,135),(244,155),(256,211))[1:]+bezier((256,211),(284,145),(363,147),(365,213))[1:]+bezier((365,213),(374,267),(312,310),(256,342))[1:]
        p.d.line([p.p(*xy) for xy in pts],fill=white,width=round(20*SCALE),joint="curve"); p.line([(180,255),(225,255),(244,224),(275,287),(295,252),(337,252)],a,13)
    elif kind=="person":
        p.ellipse((214,148,298,232),None,white,15); p.arc((154,225,358,374),180,360,white,19); p.line([(155,300),(155,328)],white,17); p.line([(357,300),(357,328)],white,17)
    elif kind=="calculator":
        p.rect((154,135,358,377),None,white,15,24); p.rect((184,165,328,217),a,None,0,10)
        for x in (202,256,310):
            for y in (256,310,354): p.ellipse((x-13,y-13,x+13,y+13),fill=white if x!=310 or y!=354 else a)
    elif kind=="translation bubbles":
        p.rect((136,161,282,286),None,white,14,24); p.poly([(179,282),(166,314),(211,285)],white); p.rect((232,224,376,349),None,a,14,24); p.poly([(325,345),(350,371),(350,344)],a); p.line([(176,216),(244,216)],white,12); p.line([(275,281),(342,281)],a,12)
    elif kind=="shortcut arrows":
        p.line([(158,257),(222,193),(266,193)],white,19); p.poly([(261,174),(294,193),(261,212)],a); p.line([(354,256),(290,320),(246,320)],a,19); p.poly([(251,301),(218,320),(251,339)],white)
    elif kind=="video camera":
        p.rect((140,183,320,329),None,white,15,25); p.poly([(319,222),(372,194),(372,318),(319,290)],a)
    elif kind=="triangle":
        p.poly([(256,150),(371,350),(325,350),(256,231),(187,350),(141,350)],white); p.line([(186,350),(326,350)],a,14)
    elif kind=="play button":
        p.rect((133,180,379,332),None,white,15,42); p.poly([(227,211),(227,301),(310,256)],a)
    elif kind=="music play":
        p.ellipse((146,146,366,366),None,white,15); p.poly([(220,199),(220,311),(315,255)],a)
    elif kind=="camera frame":
        p.rect((148,148,364,364),None,white,17,55); p.ellipse((202,202,310,310),None,a,15); p.ellipse((317,183,338,204),fill=white)
    elif kind=="sound waves":
        p.ellipse((142,142,370,370),None,white,14); p.arc((183,202,330,269),195,345,a,14); p.arc((194,241,320,301),195,345,white,13); p.arc((209,279,308,326),195,345,a,12)
    elif kind=="paper plane":
        p.poly([(137,247),(374,150),(310,365),(250,291),(205,327),(211,265)],None,white,11); p.line([(210,265),(374,150),(250,291)],a,13)
    elif kind=="game controller":
        p.arc((142,192,370,348),180,360,white,19); p.line([(143,270),(130,331)],white,19); p.line([(370,270),(382,331)],white,19); p.line([(186,266),(224,266)],a,15); p.line([(205,247),(205,285)],a,15); p.ellipse((296,248,317,269),fill=white); p.ellipse((331,277,352,298),fill=a)
    elif kind=="mail tile":
        p.rect((128,171,384,341),None,white,14,25); p.line([(137,185),(256,276),(375,185)],a,15)
    elif kind=="community bubble":
        p.ellipse((160,160,282,282),None,white,15); p.ellipse((232,222,354,344),None,a,15); p.ellipse((205,201,220,216),fill=white); p.ellipse((246,201,261,216),fill=white); p.line([(192,265),(224,278)],white,10); p.line([(267,324),(300,309)],a,10)
    elif kind=="orbit flower":
        for ang in range(0,360,60):
            x=256+66*math.cos(math.radians(ang)); y=256+66*math.sin(math.radians(ang)); p.ellipse((x-38,y-38,x+38,y+38),None,white,10)
        p.ellipse((236,236,276,276),fill=a)
    elif kind=="play triangle":
        p.poly([(168,149),(350,256),(168,363)],None,white,13); p.line([(183,172),(287,256),(183,340)],a,15)
    else:
        p.ellipse((170,170,342,342),None,white,17); p.line([(197,256),(315,256)],a,15)

def make_icon(name, index, dark, kind=None):
    top,bottom,fg,accent=palette_for(index,dark)
    image=Image.new("RGBA",(CANVAS,CANVAS),(0,0,0,0))
    shadow=Image.new("RGBA",(CANVAS,CANVAS),(0,0,0,0)); sd=ImageDraw.Draw(shadow)
    sd.rounded_rectangle((88,82,936,930),radius=206,fill=(0,0,0,112 if dark else 82))
    shadow=shadow.filter(ImageFilter.GaussianBlur(30)); image.alpha_composite(shadow)
    mask=Image.new("L",(CANVAS,CANVAS),0); md=ImageDraw.Draw(mask)
    md.rounded_rectangle((74,64,950,940),radius=214,fill=255)
    # vertical gradient background clipped to a smooth squircle
    grad=Image.new("RGBA",(CANVAS,CANVAS),(0,0,0,0)); gd=ImageDraw.Draw(grad)
    c1,c2=rgb(top),rgb(bottom)
    for y in range(CANVAS):
        t=y/(CANVAS-1); c=tuple(round(c1[j]*(1-t)+c2[j]*t) for j in range(3))
        gd.line((0,y,CANVAS,y),fill=(*c,255))
    grad.putalpha(mask); image.alpha_composite(grad)
    # a translucent glass body with broad, non-directional reflections
    plate=Image.new("RGBA",(CANVAS,CANVAS),(0,0,0,0)); pd=ImageDraw.Draw(plate)
    pd.rounded_rectangle((84,74,940,930),radius=210,fill=(255,255,255,24 if not dark else 10))
    plate.putalpha(ImageChops_multiply(plate.getchannel("A"),mask)); image.alpha_composite(plate)
    # soft refracted light pools, kept away from a hard horizontal band
    glow=Image.new("RGBA",(CANVAS,CANVAS),(0,0,0,0)); gl=ImageDraw.Draw(glow)
    gl.ellipse((35,20,820,640),fill=(255,255,255,86 if not dark else 50))
    gl.ellipse((470,520,1050,1080),fill=(106,188,255,28 if not dark else 54))
    glow=glow.filter(ImageFilter.GaussianBlur(145)); glow.putalpha(ImageChops_multiply(glow.getchannel("A"),mask)); image.alpha_composite(glow)
    # diagonal specular sweep and a fine reflected rim
    sheen=Image.new("RGBA",(CANVAS,CANVAS),(0,0,0,0)); sh=ImageDraw.Draw(sheen)
    sh.polygon([(96,125),(260,72),(990,606),(944,710),(800,664),(200,220)],fill=(255,255,255,35 if not dark else 20))
    sh.arc((118,105,906,900),193,285,fill=(255,255,255,125 if not dark else 88),width=5*SCALE)
    sh.arc((170,160,848,852),198,285,fill=(255,255,255,54 if not dark else 35),width=3*SCALE)
    sheen=sheen.filter(ImageFilter.GaussianBlur(28))
    sheen.putalpha(ImageChops_multiply(sheen.getchannel("A"),mask)); image.alpha_composite(sheen)
    # floating frosted lens beneath each pictogram
    lens=Image.new("RGBA",(CANVAS,CANVAS),(0,0,0,0)); ld=ImageDraw.Draw(lens)
    ld.rounded_rectangle((236,230,788,782),radius=205,fill=(255,255,255,18 if not dark else 10),outline=(255,255,255,48 if not dark else 30),width=3*SCALE)
    lens=lens.filter(ImageFilter.GaussianBlur(11)); lens.putalpha(ImageChops_multiply(lens.getchannel("A"),mask)); image.alpha_composite(lens)
    border=Image.new("RGBA",(CANVAS,CANVAS),(0,0,0,0)); bd=ImageDraw.Draw(border)
    bd.rounded_rectangle((75,65,949,939),radius=213,outline=(255,255,255,100 if not dark else 74),width=5*SCALE)
    image.alpha_composite(border)
    # subtle glyph emboss, glow, then crisp pictogram
    glyph_layer=Image.new("RGBA",(CANVAS,CANVAS),(0,0,0,0)); painter=Painter(ImageDraw.Draw(glyph_layer))
    draw_symbol(kind or name,painter,(*rgb(fg),255),(*rgb(accent),255))
    from PIL import ImageChops
    shadow_alpha=ImageChops.offset(glyph_layer.getchannel("A"),0,12).point(lambda v: round(v*(0.32 if dark else 0.20)))
    glyph_shadow=Image.new("RGBA",(CANVAS,CANVAS),(6,12,24,0)); glyph_shadow.putalpha(shadow_alpha.filter(ImageFilter.GaussianBlur(12*SCALE))); image.alpha_composite(glyph_shadow)
    glyph_glow=glyph_layer.filter(ImageFilter.GaussianBlur(13*SCALE))
    glyph_glow.putalpha(glyph_glow.getchannel("A").point(lambda v: round(v*(0.22 if dark else 0.11))))
    image.alpha_composite(glyph_glow); image.alpha_composite(glyph_layer)
    image=image.resize((SIZE,SIZE),Image.Resampling.LANCZOS)
    return image

def ImageChops_multiply(a,b):
    from PIL import ImageChops
    return ImageChops.multiply(a,b)

def make_fallback(kind, dark, index):
    top,bottom,fg,accent=palette_for(index,dark)
    image=make_icon("generic",index,dark,"gear")
    if kind=="iconmask":
        # solid alpha squircle used by launchers as a mask
        image=Image.new("RGBA",(SIZE,SIZE),(0,0,0,0)); d=ImageDraw.Draw(image); d.rounded_rectangle((36,36,476,476),radius=108,fill=(255,255,255,255))
    elif kind=="iconupon":
        image=Image.new("RGBA",(SIZE,SIZE),(0,0,0,0)); d=ImageDraw.Draw(image); d.rounded_rectangle((28,28,484,484),radius=112,outline=(255,255,255,72 if not dark else 54),width=4); d.arc((38,38,474,474),190,285,fill=(255,255,255,120),width=5)
    return image

def write_xml():
    vals=ROOT/"app/src/main/res/values"; vals.mkdir(parents=True,exist_ok=True)
    arrays=ET.Element("resources")
    for name,items in (("icon_names",[x[0] for x in ICONS]),("icon_labels",[x[1] for x in ICONS])):
        arr=ET.SubElement(arrays,"string-array",{"name":name})
        for item in items: ET.SubElement(arr,"item").text=item
    ET.indent(arrays,space="    ")
    (vals/"arrays.xml").write_text('<?xml version="1.0" encoding="utf-8"?>\n'+ET.tostring(arrays,encoding="unicode")+'\n')
    resources=ET.Element("resources")
    for arr,items in (("iconback",["iconback_one","iconback_two","iconback_three"]),("iconmask",["iconmask"]),("iconupon",["iconupon"])):
        node=ET.SubElement(resources,"array",{"name":arr})
        for item in items: ET.SubElement(node,"item").text="@drawable/"+item
    ET.indent(resources,space="    ")
    (vals/"iconpack_arrays.xml").write_text('<?xml version="1.0" encoding="utf-8"?>\n'+ET.tostring(resources,encoding="unicode")+'\n')
    root=ET.Element("resources")
    for name,label,kind,targets in ICONS:
        for pkg,activity in targets:
            cls=activity if activity.startswith(pkg) else (pkg+activity if activity.startswith(".") else activity)
            ET.SubElement(root,"item",{"component":f"ComponentInfo{{{pkg}/{cls}}}","drawable":name})
    ET.indent(root,space="    ")
    xml=ET.tostring(root,encoding="unicode")
    out=ROOT/"app/src/main/res/xml/appfilter.xml"; out.parent.mkdir(parents=True,exist_ok=True)
    out.write_text('<?xml version="1.0" encoding="utf-8"?>\n'+xml+'\n')

def main():
    write_xml()
    for flavor,dark in (("light",False),("dark",True)):
        out=ROOT/f"app/src/{flavor}/res/drawable-nodpi"; out.mkdir(parents=True,exist_ok=True)
        for i,(name,label,kind,targets) in enumerate(ICONS):
            make_icon(name,i,dark,kind).save(out/f"{name}.png",optimize=True)
        for j,n in enumerate(("iconback_one","iconback_two","iconback_three")):
            make_icon("generic",j+1,dark,"house").save(out/f"{n}.png",optimize=True)
        make_fallback("iconmask",dark,0).save(out/"iconmask.png",optimize=True)
        make_fallback("iconupon",dark,0).save(out/"iconupon.png",optimize=True)
        source=ROOT/f"artwork/{flavor}-pack-launcher.png"
        if source.exists():
            with Image.open(source) as artwork:
                artwork.convert("RGBA").resize((SIZE,SIZE),Image.Resampling.LANCZOS).save(out/"pack_launcher.png",optimize=True)
        else:
            make_icon("pack_launcher",2 if dark else 0,dark,"flower").save(out/"pack_launcher.png",optimize=True)
    print(f"Generated {len(ICONS)} icons × 2 variants and appfilter mappings.")
    print(f"Mappings: {sum(len(x[3]) for x in ICONS)} package/activity entries.")

if __name__=="__main__": main()
