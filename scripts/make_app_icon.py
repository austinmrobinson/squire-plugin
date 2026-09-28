"""
Squire's app icon: Sam the squire (the plugin's 13x13 helm sprite, doubled) on a red pixel tile with stepped
corners, the game's bevel and a dark outline. Writes assets/app-icon-1024.png and the Plugin Hub icon.png.
Run from plugin/: python3 scripts/make_app_icon.py
"""
import zlib, struct
SPRITE=[".pPPP........","pppPPP.......","ppdpPPP..LM..","pd.dpMLKLKLM.","pd.dMLKMMKLKL","pd.DMKMKMLMLM",".PdDKMMMKKKKM",".PdDMDKfffffK",".PdDKKFkFFFkD","Ppd.DDFFFnFFD","dd..DDfFFFFFD","...DDMDFmmFfD","....DDDfFFFDD"]
PAL={"P":"313BBF","p":"2D2D84","d":"222154","L":"B4B4B4","M":"848484","D":"555555","K":"3A3A3A","F":"F0C798","f":"E9A076","k":"050505","n":"E08E71","m":"D19A7D"}
def rgb(h): return tuple(int(h[i:i+2],16) for i in (0,2,4))
N=42
CUT=[5,3,2,1,1]
def inside(x,y):
    for i,c in enumerate(CUT):
        if y==i or y==N-1-i:
            return c<=x<N-c
    return True
def icon(bands, light, dark, outline, scale=2, oy=0):
    img=[[None]*N for _ in range(N)]
    for y in range(N):
        for x in range(N):
            if inside(x,y):
                img[y][x]=bands[min(len(bands)-1, y*len(bands)//N)]
    # bevel
    for y in range(N):
        for x in range(N):
            if img[y][x] is None: continue
            up = y==0 or not inside(x,y-1); left = x==0 or not inside(x-1,y)
            down = y==N-1 or not inside(x,y+1); right = x==N-1 or not inside(x+1,y)
            if up or left: img[y][x]=light
            elif down or right: img[y][x]=dark
    S=len(SPRITE)*scale
    # One pixel left of box-centre: the face draws the eye, the plume is light
    ox=7; oy0=(N-S)//2+oy
    spr=[[None]*N for _ in range(N)]
    for sy,row in enumerate(SPRITE):
        for sx,ch in enumerate(row):
            if ch in PAL:
                for dy in range(scale):
                    for dx in range(scale):
                        spr[oy0+sy*scale+dy][ox+sx*scale+dx]=rgb(PAL[ch])
    # outline: empty cells touching the sprite (4-neighbour)
    for y in range(N):
        for x in range(N):
            if spr[y][x] is None and any(0<=y+dy<N and 0<=x+dx<N and spr[y+dy][x+dx] is not None for dx,dy in ((1,0),(-1,0),(0,1),(0,-1))):
                if img[y][x] is not None: img[y][x]=outline
    for y in range(N):
        for x in range(N):
            if spr[y][x] is not None: img[y][x]=spr[y][x]
    return img
def save(img, path, px):
    rows=bytearray()
    for y in range(N*px):
        line=bytearray(b"\x00")
        for x in range(N):
            c=img[y//px][x]
            line+=(bytes((*c,255)) if c else b"\x00\x00\x00\x00")*px
        rows+=line
    rows=bytes(rows)
    def chunk(t,d): return struct.pack('>I',len(d))+t+d+struct.pack('>I',zlib.crc32(t+d)&0xffffffff)
    open(path,"wb").write(b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',N*px,N*px,8,6,0,0,0))+chunk(b'IDAT',zlib.compress(rows,9))+chunk(b'IEND',b''))
RED=([rgb(h) for h in ("E0584D","D44E44","C8453C","BC3D35","AF362F")], rgb("F59287"), rgb("7A221E"), rgb("3A0F0E"))
tile=icon(*RED, oy=0)
save(tile, "assets/app-icon-1024.png", 24)
save(tile, "icon.png", 1)
print("wrote assets/app-icon-1024.png and icon.png")
