# -*- coding: utf-8 -*-
"""Mijn Muziek naar Sonos voor Windows. No third-party runtime libraries."""
import concurrent.futures
import http.server
import ipaddress
import json
import mimetypes
import os
from pathlib import Path
import secrets
import socket
import struct
import threading
import time
import tkinter as tk
from tkinter import ttk, filedialog, messagebox, simpledialog
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from xml.sax.saxutils import escape, quoteattr

APP_NAME = 'Mijn Muziek naar Sonos'
VERSION = '0.5'
TYPES = ('.mp3', '.flac', '.wav', '.m4a', '.aac', '.ogg', '.opus')
SONOS_PORT = 1400
AV = 'urn:schemas-upnp-org:service:AVTransport:1'
RC = 'urn:schemas-upnp-org:service:RenderingControl:1'
APP_DIR = Path(os.environ.get('LOCALAPPDATA', str(Path.home()))) / 'MijnMuziekNaarSonos'
CONFIG = APP_DIR / 'settings.json'


def is_local(ip):
    try:
        addr = ipaddress.ip_address(ip)
        return addr.version == 4 and (addr.is_private or addr.is_link_local) and not addr.is_loopback
    except ValueError:
        return False


def text_of_xml_tag(root, name):
    for item in root.iter():
        if item.tag.split('}')[-1] == name:
            return (item.text or '').strip()
    return ''


def inspect_sonos(ip):
    if not is_local(ip):
        return None
    try:
        req = urllib.request.Request(f'http://{ip}:1400/xml/device_description.xml', headers={'User-Agent': 'SonosPC/0.4'})
        with urllib.request.urlopen(req, timeout=1.0) as response:
            payload = response.read(140000)
        root = ET.fromstring(payload)
        if 'sonos' not in text_of_xml_tag(root,'manufacturer').lower():
            return None
        has_av = any('AVTransport:1' in (e.text or '') for e in root.iter() if e.tag.split('}')[-1]=='serviceType')
        if not has_av:
            return None  # Don't show Sonos Sub or surround satellite as a player
        room = text_of_xml_tag(root, 'roomName') or 'Sonos'
        model = text_of_xml_tag(root, 'modelName')
        return {'ip':ip,'name':room + (' · ' + model if model else ''), 'uid':text_of_xml_tag(root,'UDN')}
    except (OSError, ValueError, ET.ParseError):
        return None


def local_ip_for(destination):
    with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as sock:
        sock.connect((destination, SONOS_PORT))
        return sock.getsockname()[0]


def discover_sonos():
    addresses = set()
    query = ('M-SEARCH * HTTP/1.1\r\nHOST:239.255.255.250:1900\r\n'
             'MAN:"ssdp:discover"\r\nMX:2\r\n'
             'ST:urn:schemas-upnp-org:device:ZonePlayer:1\r\n\r\n')
    try:
        with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as s:
            s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 2)
            s.settimeout(0.5)
            for _ in range(2):
                s.sendto(query.encode('ascii'),('239.255.255.250',1900))
            stop=time.monotonic()+3.1
            while time.monotonic()<stop:
                try:
                    data,peer=s.recvfrom(8192)
                    if b'sonos' in data.lower() or b'zoneplayer' in data.lower():
                        addresses.add(peer[0])
                except socket.timeout:
                    pass
    except OSError:
        pass
    devices = []
    for addr in addresses:
        d=inspect_sonos(addr)
        if d: devices.append(d)
    if devices:
        return sorted(devices,key=lambda x:x['name'].lower())
    # SSDP is frequently blocked by routers; scan only the local /24, never public networks.
    try:
        own=local_ip_for('192.168.1.4')
        if not is_local(own): return []
        net=ipaddress.ip_network(own+'/24',strict=False)
        ips=[str(x) for x in net.hosts() if str(x)!=own]
    except (OSError,ValueError):
        return []
    with concurrent.futures.ThreadPoolExecutor(max_workers=32) as executor:
        for device in executor.map(inspect_sonos,ips):
            if device: devices.append(device)
    return sorted(devices,key=lambda x:x['name'].lower())


def soap(ip, service, path, action, fields):
    if not is_local(ip): raise ValueError('Enkel lokale Sonos-adressen zijn toegestaan')
    body=(f'<?xml version="1.0" encoding="utf-8"?>'
          f'<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
          f's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">'
          f'<s:Body><u:{action} xmlns:u="{service}">{fields}</u:{action}></s:Body></s:Envelope>')
    req=urllib.request.Request(f'http://{ip}:{SONOS_PORT}/MediaRenderer/{path}/Control',
        data=body.encode('utf-8'),
        headers={'Content-Type':'text/xml; charset="utf-8"', 'SOAPACTION':f'"{service}#{action}"'})
    try:
        with urllib.request.urlopen(req,timeout=6) as response:
            return response.read(200000)
    except urllib.error.HTTPError as error:
        content=error.read(600)
        raise RuntimeError(f'Sonos HTTP {error.code}: {content.decode("utf-8","replace")}') from error


def get_position(ip):
    data=soap(ip,AV,'AVTransport','GetPositionInfo','<InstanceID>0</InstanceID>')
    root=ET.fromstring(data)
    return read_hms(text_of_xml_tag(root,'RelTime')), read_hms(text_of_xml_tag(root,'TrackDuration'))


def read_hms(value):
    try:
        if value=='NOT_IMPLEMENTED':return -1
        hours,minutes,seconds=value.split(':')
        return int(hours)*3600+int(minutes)*60+int(float(seconds))
    except (ValueError,AttributeError):return -1


def hhmmss(value):
    value=max(0,int(value))
    return f'{value//3600:02}:{(value//60)%60:02}:{value%60:02}'


def format_time(value):
    return '--:--' if value<0 else f'{value//60:02}:{value%60:02}'


def send_play(ip, url, path):
    name=Path(path).stem
    mime=mimetypes.guess_type(path)[0] or 'audio/mpeg'
    mime={'audio/x-flac':'audio/flac','audio/x-wav':'audio/wav'}.get(mime,mime)
    didl=('<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" '
          'xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">'
          '<item id="1" parentID="0" restricted="1">'
          f'<dc:title>{escape(name)}</dc:title><upnp:class>object.item.audioItem.musicTrack</upnp:class>'
          f'<res protocolInfo={quoteattr("http-get:*:"+mime+":*")}>{escape(url)}</res>'
          '</item></DIDL-Lite>')
    soap(ip,AV,'AVTransport','SetAVTransportURI',
         '<InstanceID>0</InstanceID><CurrentURI>'+escape(url)+'</CurrentURI>'
         '<CurrentURIMetaData>'+escape(didl)+'</CurrentURIMetaData>')
    soap(ip,AV,'AVTransport','Play','<InstanceID>0</InstanceID><Speed>1</Speed>')


def sonos_queue_start(ip, uid, uri, path):
    """Build a native Sonos queue, so next tracks do not depend on window timers."""
    if not uid or not uid.startswith('uuid:RINCON_'):
        raise RuntimeError('Sonos toestel-ID ontbreekt. Zoek opnieuw naar je luidspreker.')
    soap(ip,AV,'AVTransport','RemoveAllTracksFromQueue','<InstanceID>0</InstanceID>')
    sonos_queue_add(ip,uri,path)
    queue_uri='x-rincon-queue:'+uid[5:]+'#0'
    soap(ip,AV,'AVTransport','SetAVTransportURI',
        '<InstanceID>0</InstanceID><CurrentURI>'+escape(queue_uri)+'</CurrentURI>'
        '<CurrentURIMetaData></CurrentURIMetaData>')
    soap(ip,AV,'AVTransport','Seek',
        '<InstanceID>0</InstanceID><Unit>TRACK_NR</Unit><Target>1</Target>')
    transport(ip,'Play')


def sonos_queue_add(ip, uri, path):
    name=Path(path).stem
    mime=mimetypes.guess_type(path)[0] or 'audio/mpeg'
    didl=('<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" '
      'xmlns:dc="http://purl.org/dc/elements/1.1/" '
      'xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">'
      '<item id="1" parentID="0" restricted="1"><dc:title>'+escape(name)+'</dc:title>'
      '<upnp:class>object.item.audioItem.musicTrack</upnp:class>'
      '<res protocolInfo="http-get:*:'+escape(mime)+':*">'+escape(uri)+'</res>'
      '</item></DIDL-Lite>')
    soap(ip,AV,'AVTransport','AddURIToQueue',
      '<InstanceID>0</InstanceID><EnqueuedURI>'+escape(uri)+'</EnqueuedURI>'
      '<EnqueuedURIMetaData>'+escape(didl)+'</EnqueuedURIMetaData>'
      '<DesiredFirstTrackNumberEnqueued>0</DesiredFirstTrackNumberEnqueued>'
      '<EnqueueAsNext>0</EnqueueAsNext>')


def get_position_and_track(ip):
    data=soap(ip,AV,'AVTransport','GetPositionInfo','<InstanceID>0</InstanceID>')
    root=ET.fromstring(data)
    raw=text_of_xml_tag(root,'Track')
    try: track=int(raw)
    except ValueError:track=-1
    return (read_hms(text_of_xml_tag(root,'RelTime')),
            read_hms(text_of_xml_tag(root,'TrackDuration')),track)


def seek(ip, position):
    soap(ip,AV,'AVTransport','Seek',
         '<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>'+hhmmss(position)+'</Target>')


def transport(ip, action):
    soap(ip,AV,'AVTransport',action,
         '<InstanceID>0</InstanceID>' + ('<Speed>1</Speed>' if action=='Play' else ''))


class MediaServer:
    def __init__(self):
        self.token=secrets.token_urlsafe(30)
        self.files={}
        self.lock=threading.Lock()
        owner=self
        class Handler(http.server.BaseHTTPRequestHandler):
            server_version='SonosMusicLocal/0.4'
            def log_message(self, format, *args):pass
            def do_HEAD(self):self.send_file(False)
            def do_GET(self):self.send_file(True)
            def send_file(self,send_body):
                prefix='/music/'+owner.token+'/'
                key=self.path.split('?',1)[0]
                if not key.startswith(prefix):
                    self.send_error(404);return
                identifier=key[len(prefix):]
                with owner.lock: path=owner.files.get(identifier)
                if not path or not Path(path).is_file():
                    self.send_error(404);return
                try:
                    size=os.path.getsize(path)
                    start=0;end=size-1;partial=False
                    range_header=self.headers.get('Range','')
                    if range_header.startswith('bytes='):
                        unit=range_header[6:].split(',',1)[0].strip()
                        part1,sep,part2=unit.partition('-')
                        if not sep:raise ValueError('Invalid range')
                        if part1:
                            start=int(part1)
                            if part2:end=min(end,int(part2))
                        else:
                            count=int(part2)
                            start=max(0,size-count)
                        if start>=size or end<start:raise ValueError('Invalid range')
                        partial=True
                    self.send_response(206 if partial else 200)
                    mime=mimetypes.guess_type(path)[0] or 'audio/mpeg'
                    self.send_header('Content-Type',mime)
                    self.send_header('Content-Length',str(end-start+1))
                    self.send_header('Accept-Ranges','bytes')
                    if partial:self.send_header('Content-Range',f'bytes {start}-{end}/{size}')
                    self.send_header('Cache-Control','no-store')
                    self.send_header('Connection','close')
                    self.end_headers()
                    if not send_body:return
                    with open(path,'rb') as f:
                        f.seek(start);remaining=end-start+1
                        while remaining:
                            chunk=f.read(min(65536,remaining))
                            if not chunk:break
                            self.wfile.write(chunk)
                            remaining-=len(chunk)
                except (OSError,ValueError):
                    try:self.send_error(416,'Cannot serve this range')
                    except OSError:pass
        self.server=http.server.ThreadingHTTPServer(('0.0.0.0',0),Handler)
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True)
        self.thread.start()
    def url(self,path,sonos_ip):
        path=str(Path(path).resolve())
        identifier=secrets.token_urlsafe(16)
        with self.lock:self.files[identifier]=path
        pcip=local_ip_for(sonos_ip)
        return f'http://{pcip}:{self.server.server_address[1]}/music/{self.token}/{identifier}'
    def stop(self):self.server.shutdown();self.server.server_close()


class App:
    def __init__(self,root):
        self.root=root
        self.root.title(APP_NAME+' · Windows '+VERSION)
        self.root.geometry('1000x760'); self.root.minsize(800,650)
        self.songs=[]
        self.playlists={}
        self.autonext=tk.BooleanVar(value=True)
        self.queue_tracks=[]
        self.using_queue=False
        self.folders=[]
        self.saved_files=[]
        self.devices=[]
        self.current_song=None
        self.current_device_ip=''
        self.position=-1
        self.duration=-1
        self.position_set_at=time.monotonic()
        self.playing=False
        self.seek_dragging=False
        self.server=MediaServer()
        self.load_config()
        self.build_ui()
        self.root.protocol('WM_DELETE_WINDOW',self.close)
        self.refresh_library()
        self.root.after(300,self.start_discovery)
        self.root.after(600,self.timer_tick)
        self.root.after(2500,self.poll_position)

    def load_config(self):
        try:
            config=json.loads(CONFIG.read_text(encoding='utf-8'))
        except (OSError,ValueError):config={}
        self.folders=config.get('folders',[])
        self.saved_files=config.get('files',[])
        self.playlists=config.get('playlists',{})
        self.autonext.set(config.get('autonext',True))
        self.last_ip=config.get('last_ip','')
        self.volume_value=config.get('volume',25)

    def save_config(self):
        try:
            APP_DIR.mkdir(parents=True,exist_ok=True)
            CONFIG.write_text(json.dumps({'folders':self.folders,'files':self.saved_files,
                'playlists':self.playlists,'autonext':self.autonext.get(),'last_ip':self.current_device_ip or self.last_ip,
                'volume':self.volume.get()},indent=2,ensure_ascii=False),encoding='utf-8')
        except OSError:pass

    def build_ui(self):
        root=self.root
        style=ttk.Style(root)
        try:style.theme_use('vista')
        except tk.TclError:style.theme_use('clam')
        base=ttk.Frame(root,padding=16);base.pack(fill='both',expand=True)
        ttk.Label(base,text='Mijn Muziek → Sonos',font=('Segoe UI',21,'bold')).pack(anchor='w')
        ttk.Label(base,text='Muziek van deze Windows-pc rechtstreeks naar Sonos via je thuisnetwerk',font=('Segoe UI',10)).pack(anchor='w',pady=(0,8))
        devices=ttk.LabelFrame(base,text='Sonos-kamer',padding=9);devices.pack(fill='x',pady=5)
        line=ttk.Frame(devices);line.pack(fill='x')
        self.device_var=tk.StringVar()
        self.device_combo=ttk.Combobox(line,textvariable=self.device_var,state='readonly',width=43)
        self.device_combo.pack(side='left',fill='x',expand=True,padx=(0,8))
        self.device_combo.bind('<<ComboboxSelected>>',lambda e:self.select_device())
        ttk.Button(line,text='Zoek Sonos',command=self.start_discovery).pack(side='left')
        ttk.Label(line,text='IP:').pack(side='left',padx=(10,3))
        self.ip_var=tk.StringVar(value=self.last_ip)
        ttk.Entry(line,width=17,textvariable=self.ip_var).pack(side='left')
        ttk.Button(line,text='Gebruik IP',command=self.use_ip).pack(side='left',padx=(4,0))
        library=ttk.LabelFrame(base,text='Muziekbibliotheek',padding=9);library.pack(fill='both',expand=True,pady=6)
        actions=ttk.Frame(library);actions.pack(fill='x')
        ttk.Button(actions,text='+ Muziekbestanden',command=self.pick_files).pack(side='left',padx=(0,5))
        ttk.Button(actions,text='+ Volledige map',command=self.pick_folder).pack(side='left',padx=(0,5))
        ttk.Button(actions,text='Herlaad',command=self.refresh_library).pack(side='left')
        listrow=ttk.Frame(library);listrow.pack(fill='x',pady=(10,6))
        ttk.Label(listrow,text='Afspeellijst:').pack(side='left')
        self.list_var=tk.StringVar(value='Alle muziek')
        self.list_combo=ttk.Combobox(listrow,textvariable=self.list_var,state='readonly',width=23)
        self.list_combo.pack(side='left',padx=7)
        self.list_combo.bind('<<ComboboxSelected>>',lambda e:self.refresh_table())
        ttk.Button(listrow,text='Nieuwe',command=self.new_list).pack(side='left',padx=(0,4))
        ttk.Button(listrow,text='Voeg selectie toe',command=self.add_to_list).pack(side='left',padx=(0,4))
        ttk.Button(listrow,text='Verwijder lijst',command=self.remove_list).pack(side='left',padx=(0,10))
        ttk.Label(listrow,text='Zoeken:').pack(side='left')
        self.search_var=tk.StringVar()
        self.search_var.trace_add('write',lambda *_:self.refresh_table())
        ttk.Entry(listrow,textvariable=self.search_var).pack(side='left',fill='x',expand=True)
        self.song_table=ttk.Treeview(library,columns=('name','folder'),show='headings',selectmode='extended')
        self.song_table.heading('name',text='Nummer');self.song_table.heading('folder',text='Locatie')
        self.song_table.column('name',width=280,stretch=True);self.song_table.column('folder',width=550,stretch=True)
        vertical=ttk.Scrollbar(library,orient='vertical',command=self.song_table.yview)
        self.song_table.configure(yscrollcommand=vertical.set)
        vertical.pack(side='right',fill='y');self.song_table.pack(fill='both',expand=True)
        self.song_table.bind('<Double-1>',lambda e:self.play_selected())
        ttk.Checkbutton(base,text='Automatisch volgende nummer uit deze lijst afspelen',
            variable=self.autonext,command=self.save_config).pack(anchor='w',pady=3)
        player=ttk.LabelFrame(base,text='Speler',padding=9);player.pack(fill='x',pady=5)
        self.playing_var=tk.StringVar(value='Kies een nummer en dubbelklik om af te spelen')
        ttk.Label(player,textvariable=self.playing_var,font=('Segoe UI',11,'bold')).pack(anchor='w')
        transportrow=ttk.Frame(player);transportrow.pack(fill='x',pady=7)
        for text,command in [('⏮ Vorige',lambda:self.next_track(-1)),('◀ 10 sec',lambda:self.skip(-10)),
                ('▶ Afspelen',self.play_or_resume),('⏸ Pauze',lambda:self.control('Pause')),
                ('10 sec ▶',lambda:self.skip(10)),('Volgende ⏭',lambda:self.next_track(1)),
                ('■ Stop',lambda:self.control('Stop'))]:
            ttk.Button(transportrow,text=text,command=command).pack(side='left',padx=(0,5))
        timeline=ttk.Frame(player);timeline.pack(fill='x')
        self.clock_var=tk.StringVar(value='00:00 / --:--')
        ttk.Label(timeline,textvariable=self.clock_var,width=16).pack(side='left')
        self.progress=tk.DoubleVar(value=0)
        self.scrub=ttk.Scale(timeline,from_=0,to=1000,variable=self.progress,orient='horizontal')
        self.scrub.pack(side='left',fill='x',expand=True,padx=6)
        self.scrub.bind('<ButtonPress-1>',lambda e:setattr(self,'seek_dragging',True))
        self.scrub.bind('<ButtonRelease-1>',self.on_seek_release)
        ttk.Label(timeline,text='Volume').pack(side='left',padx=(12,5))
        self.volume=tk.IntVar(value=self.volume_value)
        vol=ttk.Scale(timeline,from_=0,to=100,orient='horizontal',length=120)
        vol.set(self.volume_value);vol.pack(side='left');vol.bind('<ButtonRelease-1>',lambda e:self.set_volume(int(vol.get())))
        self.volume=vol
        self.status=tk.StringVar(value='Klaar. Sonos en pc moeten op hetzelfde wifi/LAN-netwerk staan.')
        ttk.Label(base,textvariable=self.status,foreground='#2362a9',wraplength=880).pack(fill='x',pady=(6,0))
        self.update_playlist_choices()

    def task(self,work,success=None):
        def runner():
            try:
                value=work()
                if success:self.root.after(0,lambda:success(value))
            except Exception as exc:
                self.root.after(0,lambda:self.status.set('Fout: '+str(exc)[:240]))
        threading.Thread(target=runner,daemon=True).start()

    def start_discovery(self):
        self.status.set('Sonos-kamers zoeken…')
        self.task(discover_sonos,self.show_devices)

    def show_devices(self,found):
        self.devices=found
        self.device_combo['values']=[d['name']+' — '+d['ip'] for d in found]
        ip=self.current_device_ip or self.last_ip
        for i,d in enumerate(found):
            if d['ip']==ip:
                self.device_combo.current(i);self.current_device_ip=ip;break
        else:
            if found:self.device_combo.current(0);self.select_device()
        self.status.set(str(len(found))+' afspeelbare Sonos-kamers gevonden.' if found else
                        'Niets gevonden. Vul het IP van een Sonos-speler in en kies Gebruik IP.')

    def select_device(self):
        i=self.device_combo.current()
        if 0<=i<len(self.devices):
            d=self.devices[i];self.current_device_ip=d['ip'];self.ip_var.set(d['ip'])
            self.last_ip=d['ip'];self.save_config()

    def use_ip(self):
        ip=self.ip_var.get().strip()
        if not is_local(ip):messagebox.showerror(APP_NAME,'Geef een geldig lokaal IPv4-adres op.');return
        self.status.set('Sonos op '+ip+' controleren…')
        def done(d):
            if not d:self.status.set('Geen zelfstandige Sonos-speler gevonden op '+ip);return
            if not any(old['ip']==ip for old in self.devices):self.devices.append(d)
            self.device_combo['values']=[x['name']+' — '+x['ip'] for x in self.devices]
            self.device_combo.current(next(i for i,x in enumerate(self.devices) if x['ip']==ip))
            self.select_device();self.status.set('Verbonden met '+d['name'])
        self.task(lambda:inspect_sonos(ip),done)

    def pick_files(self):
        filenames=filedialog.askopenfilenames(title='Kies muziek',filetypes=[('Muziek',' '.join('*'+s for s in TYPES)),('Alle bestanden','*.*')])
        changed=False
        for name in filenames:
            if name not in self.saved_files and Path(name).suffix.lower() in TYPES:
                self.saved_files.append(name);changed=True
        if changed:self.save_config();self.refresh_library()

    def pick_folder(self):
        folder=filedialog.askdirectory(title='Kies muziekmap (ook submappen)')
        if folder and folder not in self.folders:
            self.folders.append(folder);self.save_config();self.refresh_library()

    def refresh_library(self):
        self.status.set('Muziekmappen lezen…')
        self.task(self.collect_files,self.set_library)

    def collect_files(self):
        paths=set()
        for path in self.saved_files:
            if Path(path).is_file() and Path(path).suffix.lower() in TYPES:paths.add(str(Path(path)))
        for folder in self.folders:
            if not os.path.isdir(folder):continue
            for base,dirs,files in os.walk(folder):
                dirs[:]=[d for d in dirs if not d.startswith('.')]
                for name in files:
                    if name.lower().endswith(TYPES):paths.add(str(Path(base)/name))
                    if len(paths)>40000:break
                if len(paths)>40000:break
        return sorted(paths,key=lambda x:(Path(x).name.lower(),x.lower()))

    def set_library(self,paths):
        self.songs=paths;self.refresh_table()
        self.status.set(f'{len(paths)} muziekbestanden beschikbaar.')

    def update_playlist_choices(self):
        self.list_combo['values']=['Alle muziek']+sorted(self.playlists.keys(),key=str.casefold)
        if self.list_var.get() not in self.list_combo['values']:self.list_var.set('Alle muziek')

    def refresh_table(self):
        if not hasattr(self,'song_table'):return
        self.song_table.delete(*self.song_table.get_children())
        query=self.search_var.get().casefold().strip()
        pl=self.list_var.get();allowed=set(self.playlists.get(pl,[])) if pl!='Alle muziek' else None
        for idx,p in enumerate(self.songs):
            if allowed is not None and p not in allowed:continue
            if query and query not in p.casefold():continue
            self.song_table.insert('', 'end',iid=str(idx),values=(Path(p).stem,str(Path(p).parent)))

    def new_list(self):
        name=simpledialog.askstring('Nieuwe afspeellijst','Naam van de lijst:',parent=self.root)
        if not name:return
        name=name.strip()
        if not name or name=='Alle muziek' or name in self.playlists:messagebox.showerror(APP_NAME,'Naam niet geldig of al in gebruik.');return
        self.playlists[name]=[];self.save_config();self.update_playlist_choices();self.list_var.set(name);self.refresh_table()

    def add_to_list(self):
        selected=self.song_table.selection()
        if not selected:messagebox.showinfo(APP_NAME,'Selecteer eerst een of meerdere nummers (Ctrl of Shift).');return
        name=self.list_var.get()
        if name=='Alle muziek':
            names=list(self.playlists)
            if not names: self.new_list();name=self.list_var.get()
            else:
                # Show a selection dialogue instead of silently choosing a playlist.
                name=simpledialog.askstring('Afspeellijst','Naam van bestaande afspeellijst:\n'+', '.join(names),parent=self.root)
            if name not in self.playlists:return
        values=self.playlists[name]
        for identifier in selected:
            path=self.songs[int(identifier)]
            if path not in values:values.append(path)
        self.save_config();self.refresh_table();self.status.set('Nummers toegevoegd aan '+name)

    def remove_list(self):
        name=self.list_var.get()
        if name=='Alle muziek':messagebox.showinfo(APP_NAME,'Kies eerst een afspeellijst.');return
        if messagebox.askyesno(APP_NAME,f'Afspeellijst "{name}" verwijderen?\nDe muziekbestanden blijven bestaan.'):
            self.playlists.pop(name,None);self.save_config();self.update_playlist_choices();self.refresh_table()

    def selected_song(self):
        selected=self.song_table.selection()
        if not selected:return None
        return self.songs[int(selected[0])]

    def play_selected(self):
        path=self.selected_song()
        if path:self.play_song(path)

    def speaker_ip(self):
        ip=self.current_device_ip or self.ip_var.get().strip()
        if not is_local(ip):raise RuntimeError('Zoek of kies eerst een Sonos-kamer')
        return ip

    def play_song(self,path):
        shown=[self.songs[int(i)] for i in self.song_table.get_children('')]
        try:start=shown.index(path)
        except ValueError:start=0
        batch=(shown[start:start+150] if self.autonext.get() else [path]) or [path]
        self.queue_tracks=batch
        self.using_queue=len(batch)>1
        self.current_song=path;self.position=0;self.duration=-1;self.playing=False
        self.position_set_at=time.monotonic();self.playing_var.set('▶  '+Path(path).name)
        self.status.set('Muziek naar Sonos sturen…')
        def work():
            ip=self.speaker_ip()
            url=self.server.url(path,ip)
            target=inspect_sonos(ip)
            use_queue=self.using_queue and target and target.get('uid','').startswith('uuid:RINCON_')
            if use_queue:sonos_queue_start(ip,target['uid'],url,path)
            else:send_play(ip,url,path)
            self.root.after(0,lambda:self.started_playing(ip,len(batch),bool(use_queue)))
            if use_queue:
                total=1
                for nxt in batch[1:]:
                    try:
                        sonos_queue_add(ip,self.server.url(nxt,ip),nxt)
                        total+=1
                    except Exception as e:
                        self.root.after(0,lambda e=e:self.status.set('Nummer in wachtrij mislukt: '+str(e)[:120]))
                self.root.after(0,lambda:self.status.set(
                    f'Automatisch volgende nummer · {total} nummers in Sonos-wachtrij · houd pc aan'))
            return ip
        self.task(work)

    def started_playing(self,ip,count,queued):
        self.current_device_ip=ip;self.last_ip=ip;self.playing=True
        self.position_set_at=time.monotonic()
        self.status.set(f'Speelt op Sonos · wachtrij wordt gevuld ({count} nummers)' if queued
                        else 'Speelt op Sonos · houd pc op hetzelfde netwerk')
        self.save_config()

    def play_or_resume(self):
        if not self.current_song:
            self.play_selected();return
        self.control('Play')

    def control(self,action):
        try:ip=self.speaker_ip()
        except RuntimeError as e:self.status.set(str(e));return
        def success(_):
            self.position=self.calculated_position();self.position_set_at=time.monotonic()
            self.playing=action=='Play'
            if action=='Stop':self.position=0
            self.status.set({'Play':'Speelt','Pause':'Gepauzeerd','Stop':'Gestopt'}.get(action,action))
        self.task(lambda:transport(ip,action),success)

    def calculated_position(self):
        if self.position<0:return -1
        additional=int(time.monotonic()-self.position_set_at) if self.playing else 0
        return min(self.duration,self.position+additional) if self.duration>0 else self.position+additional

    def skip(self,seconds):
        if not self.current_song:return
        # Query real Sonos position immediately, to avoid stale progress state.
        def work():
            ip=self.speaker_ip();pos,dur=get_position(ip)
            base=pos if pos>=0 else max(0,self.calculated_position())
            target=max(0,base+seconds)
            if dur>0:target=min(dur-1,target)
            seek(ip,target)
            return target,dur
        def done(result):
            self.position,self.duration=result;self.position_set_at=time.monotonic()
            self.status.set(f'Gesprongen naar {format_time(self.position)}')
        self.task(work,done)

    def on_seek_release(self,event):
        self.seek_dragging=False
        if not self.current_song or self.duration<=0:return
        target=int(self.progress.get()*self.duration/1000)
        ip=self.speaker_ip()
        def done(_):self.position=target;self.position_set_at=time.monotonic()
        self.task(lambda:seek(ip,target),done)

    def next_track(self,direction):
        if not self.songs:return
        selected=[self.songs[int(i)] for i in self.song_table.get_children('')]
        if not selected:return
        try:index=selected.index(self.current_song)
        except ValueError:index=-1
        self.play_song(selected[max(0,min(len(selected)-1,index+direction))])

    def set_volume(self,value):
        try:ip=self.speaker_ip()
        except RuntimeError:return
        self.task(lambda:soap(ip,RC,'RenderingControl','SetVolume',
            '<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>'+str(value)+'</DesiredVolume>'))
        self.save_config()

    def timer_tick(self):
        if not self.seek_dragging:
            pos=self.calculated_position()
            self.clock_var.set(format_time(max(0,pos))+' / '+format_time(self.duration))
            if self.duration>0:self.progress.set(min(1000,max(0,pos)*1000/self.duration))
        self.root.after(500,self.timer_tick)

    def poll_position(self):
        if self.current_song and self.current_device_ip:
            def work():return get_position_and_track(self.current_device_ip)
            def done(result):
                pos,duration,index=result
                if self.using_queue and 0<index<=len(self.queue_tracks):
                    actual=self.queue_tracks[index-1]
                    if actual!=self.current_song:
                        self.current_song=actual
                        self.playing_var.set('▶  '+Path(actual).name)
                if pos>=0:self.position=pos;self.position_set_at=time.monotonic()
                if duration>0:self.duration=duration
            self.task(work,done)
        self.root.after(3500,self.poll_position)

    def close(self):
        self.save_config()
        try:self.server.stop()
        finally:self.root.destroy()


def main():
    root=tk.Tk()
    try:App(root);root.mainloop()
    except Exception as error:
        messagebox.showerror(APP_NAME,f'Opstarten mislukt: {error}')
        raise

if __name__=='__main__':main()