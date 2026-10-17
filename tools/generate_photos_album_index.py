#!/usr/bin/env python3
"""Generate a metadata-only index for regular Photos albums (read-only DB access)."""
import argparse, json, os, sqlite3, sys, tempfile
from datetime import datetime
from pathlib import Path

SUPPORTED = {'.jpg', '.jpeg', '.png', '.webp', '.heic', '.heif'}
SCHEMA_VERSION = 1

def connect(path):
    db = sqlite3.connect(path.resolve().as_uri() + '?mode=ro&immutable=1', uri=True, timeout=15)
    db.execute('pragma query_only=on')
    return db

def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('library', type=Path, help='Photos Library.photoslibrary package')
    ap.add_argument('--output', type=Path, help='output sidecar path; defaults to library parent')
    args = ap.parse_args()
    library = args.library.resolve(); originals = (library / 'originals').resolve()
    db_path = library / 'database' / 'Photos.sqlite'
    if not db_path.is_file() or not originals.is_dir(): ap.error('library database or originals directory not found')
    output = args.output or (library.parent / 'PhotosAlbumIndex.json')
    try: db = connect(db_path)
    except sqlite3.Error as e:
        print(f'Cannot open Photos database read-only: {e}', file=sys.stderr); return 2
    try:
        # ZKIND=2 and Z_ENT=34 are the regular user-created album records observed
        # in the current Photos schema. UUID, rather than title or row PK, is identity.
        query = '''SELECT a.ZUUID,a.ZTITLE,a.ZSTARTDATE,a.ZENDDATE,x.Z_3ASSETS,
                          s.ZDIRECTORY,s.ZFILENAME,s.ZKIND
                   FROM ZGENERICALBUM a
                   LEFT JOIN Z_34ASSETS x ON x.Z_34ALBUMS=a.Z_PK
                   LEFT JOIN ZASSET s ON s.Z_PK=x.Z_3ASSETS
                   WHERE a.Z_ENT=34 AND a.ZKIND=2 AND a.ZTRASHEDSTATE=0'''
        groups = {}
        for aid,title,start,end,asset_id,directory,filename,kind in db.execute(query):
            if not aid: continue
            g=groups.setdefault(aid, {'id':str(aid), 'title':str(title or 'Untitled album'), 'start':start, 'end':end, 'refs':[]})
            if asset_id is not None: g['refs'].append((directory,filename,kind))
    except sqlite3.Error as e:
        print(f'Photos database schema/query is unsupported: {e}', file=sys.stderr); return 2
    finally: db.close()
    totals={'albums':len(groups),'membership_links':0,'linked':0,'missing':0,'unsupported':0,'duplicate':0,'out_of_root':0,'unreadable':0}
    validation_cache={}
    album_path_membership={}
    albums=[]
    for aid,g in groups.items():
        paths=[]; seen=set(); totals['membership_links'] += len(g['refs'])
        for directory,filename,kind in g['refs']:
            if not directory or not filename or kind not in (0,1): totals['unsupported']+=1; continue
            rel=Path(str(directory)) / str(filename)
            rel_key=rel.as_posix()
            if rel_key in seen: totals['duplicate']+=1; continue
            seen.add(rel_key)
            suffix=rel.suffix.lower()
            if suffix not in SUPPORTED: totals['unsupported']+=1; continue
            outcome=validation_cache.get(rel_key)
            if outcome is None:
                # Resolve each distinct asset once even when several albums contain it.
                candidate=(originals / rel).resolve()
                try:
                    candidate.relative_to(originals)
                except ValueError:
                    outcome=('out_of_root', None)
                else:
                    if not candidate.is_file(): outcome=('missing', None)
                    elif not os.access(candidate, os.R_OK): outcome=('unreadable', None)
                    else: outcome=('linked', candidate.relative_to(originals).as_posix())
                validation_cache[rel_key]=outcome
            status, safe_path=outcome
            totals[status]+=1
            if status == 'linked':
                paths.append(safe_path)
                album_path_membership[safe_path]=album_path_membership.get(safe_path,0)+1
        dates=[d for d in (g['start'],g['end']) if d is not None]
        ym=None
        if dates:
            try: ym=datetime.fromtimestamp(float(max(dates))+978307200).strftime('%Y-%m')
            except (ValueError,OverflowError,OSError): pass
        albums.append({'id':g['id'],'title':g['title'],'yearMonth':ym,'photoCount':len(paths),'paths':paths})
    payload={'version':SCHEMA_VERSION,'pathRoot':'Photos Library.photoslibrary/originals','albums':albums}
    output.parent.mkdir(parents=True,exist_ok=True)
    fd,tmp=tempfile.mkstemp(prefix=output.name+'.',dir=output.parent)
    try:
        with os.fdopen(fd,'w',encoding='utf-8') as f: json.dump(payload,f,ensure_ascii=False,separators=(',',':')); f.write('\n')
        os.replace(tmp,output)
    finally:
        if os.path.exists(tmp): os.unlink(tmp)
    print('Index written; aggregate validation (no photo paths emitted):')
    for k,v in totals.items(): print(f'{k}: {v}')
    print(f'index_albums: {len(albums)}')
    print(f'unique_validated_paths: {len(validation_cache)}')
    print(f'paths_referenced_by_multiple_albums: {sum(1 for n in album_path_membership.values() if n > 1)}')
    return 0
if __name__=='__main__': raise SystemExit(main())
