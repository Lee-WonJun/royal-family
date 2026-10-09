"""Package a local recording in the presentation and its companion PDF.

PPTX follows Microsoft Open XML's picture/media relationships and timing tree.
PDF uses an ISO 32000 Sound action with signed big-endian PCM, plus the original
M4A as an attachment. Playback depends on the recipient's PDF viewer.
"""
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
from lxml import etree as ET
import json
import sys

NS = {
    'p': 'http://schemas.openxmlformats.org/presentationml/2006/main',
    'a': 'http://schemas.openxmlformats.org/drawingml/2006/main',
    'r': 'http://schemas.openxmlformats.org/officeDocument/2006/relationships',
    'p14': 'http://schemas.microsoft.com/office/powerpoint/2010/main',
    'rel': 'http://schemas.openxmlformats.org/package/2006/relationships',
    'ct': 'http://schemas.openxmlformats.org/package/2006/content-types',
}
for prefix in ('p', 'a', 'r', 'p14'):
    ET.register_namespace(prefix, NS[prefix])

def element(prefix, tag, **attrs):
    return ET.Element('{'+NS[prefix]+'}'+tag, **attrs)

def embed_pptx(path, audio):
    with ZipFile(path) as archive:
        parts = {name: archive.read(name) for name in archive.namelist()}
    slide = ET.fromstring(parts['ppt/slides/slide1.xml'])
    pics = slide.findall('.//p:pic', NS)
    pic = pics[-1]
    extent = pic.find('p:spPr/a:xfrm/a:ext', NS)
    if (int(extent.get('cx')), int(extent.get('cy'))) != (204*9525, 204*9525):
        raise ValueError('Expected centered audio button hit area')
    cnv = pic.find('p:nvPicPr/p:cNvPr', NS)
    shape_id = cnv.get('id')
    cnv.set('name', 'Play embedded family call')
    link = element('a', 'hlinkClick', action='ppaction://media')
    cnv.insert(0, link)
    nv = pic.find('p:nvPicPr/p:nvPr', NS)
    audio_ref = element('a', 'audioFile')
    audio_ref.set('{'+NS['r']+'}link', 'rIdFamilyAudio')
    nv.insert(0, audio_ref)
    ext_lst = element('p', 'extLst')
    ext = element('p', 'ext', uri='{DAA4B4D4-6D71-4841-9C94-3DE7FCFB9230}')
    media = element('p14', 'media')
    media.set('{'+NS['r']+'}embed', 'rIdFamilyMedia')
    ext.append(media); ext_lst.append(ext); nv.append(ext_lst)

    timing = element('p','timing')
    nodes = element('p','tnLst'); par=element('p','par')
    root=element('p','cTn',id='1',dur='indefinite',restart='never',nodeType='tmRoot')
    children=element('p','childTnLst'); sound=element('p','audio',isNarration='0')
    common=element('p','cMediaNode',vol='100000')
    clock=element('p','cTn',id='2',fill='hold',display='0')
    conditions=element('p','stCondLst'); conditions.append(element('p','cond',delay='indefinite'))
    clock.append(conditions); common.append(clock)
    target=element('p','tgtEl'); target.append(element('p','spTgt',spid=shape_id))
    common.append(target); sound.append(common); children.append(sound)
    root.append(children); par.append(root); nodes.append(par); timing.append(nodes)
    existing=slide.find('p:timing',NS)
    if existing is not None: raise ValueError('Unexpected existing audio timing')
    trailing=slide.find('p:extLst',NS)
    slide.insert(list(slide).index(trailing) if trailing is not None else len(slide),timing)

    relationships=ET.fromstring(parts['ppt/slides/_rels/slide1.xml.rels'])
    for id_, kind in [('rIdFamilyAudio',NS['r']+'/audio'),('rIdFamilyMedia','http://schemas.microsoft.com/office/2007/relationships/media')]:
        relationships.append(element('rel','Relationship',Id=id_,Type=kind,Target='../media/family-call.m4a'))
    types=ET.fromstring(parts['[Content_Types].xml'])
    if not types.xpath('ct:Default[@Extension="m4a"]',namespaces=NS):
        types.append(element('ct','Default',Extension='m4a',ContentType='audio/mp4'))
    parts['ppt/slides/slide1.xml']=ET.tostring(slide,encoding='UTF-8',xml_declaration=True,standalone=True)
    parts['ppt/slides/_rels/slide1.xml.rels']=ET.tostring(relationships,encoding='UTF-8',xml_declaration=True,standalone=True)
    parts['[Content_Types].xml']=ET.tostring(types,encoding='UTF-8',xml_declaration=True,standalone=True)
    parts['ppt/media/family-call.m4a']=audio.read_bytes()
    with ZipFile(path,'w',ZIP_DEFLATED) as archive:
        for name,data in parts.items(): archive.writestr(name,data)
    print(json.dumps({'embedded_audio':audio.name,'shape_id':shape_id,'bytes':audio.stat().st_size}))

def embed_pdf(source, destination, audio, pcm):
    from pypdf import PdfReader, PdfWriter
    from pypdf.generic import (ArrayObject, BooleanObject, DecodedStreamObject,
        DictionaryObject, FloatObject, NameObject, NumberObject, TextStringObject)
    if destination.exists(): raise FileExistsError(destination)
    writer=PdfWriter(clone_from=PdfReader(source))
    raw_pcm=pcm.read_bytes()
    if pcm.suffix.lower()=='.wav':
        import array,wave
        with wave.open(str(pcm),'rb') as wav:
            if (wav.getframerate(),wav.getnchannels(),wav.getsampwidth())!=(22050,1,2):
                raise ValueError('Expected 22050 Hz mono PCM16 WAV')
            samples=array.array('h',wav.readframes(wav.getnframes()))
            samples.byteswap()  # WAV PCM16 is little-endian; PDF Sound is big-endian.
            raw_pcm=samples.tobytes()
    sound=DecodedStreamObject(); sound.set_data(raw_pcm)
    sound.update({NameObject('/Type'):NameObject('/Sound'),NameObject('/R'):NumberObject(22050),
                  NameObject('/C'):NumberObject(1),NameObject('/B'):NumberObject(16),NameObject('/E'):NameObject('/Signed')})
    sound_ref=writer._add_object(sound.flate_encode())
    action=DictionaryObject({NameObject('/S'):NameObject('/Sound'),NameObject('/Sound'):sound_ref,
        NameObject('/Volume'):FloatObject(1),NameObject('/Synchronous'):BooleanObject(False),
        NameObject('/Repeat'):BooleanObject(False),NameObject('/Mix'):BooleanObject(False)})
    page=writer.pages[0]; sx=float(page.mediabox.width)/960; sy=float(page.mediabox.height)/540
    rect=ArrayObject([FloatObject(v) for v in [378*sx,168*sy,582*sx,372*sy]])
    annot=DictionaryObject({NameObject('/Type'):NameObject('/Annot'),NameObject('/Subtype'):NameObject('/Link'),
        NameObject('/Rect'):rect,NameObject('/Border'):ArrayObject([NumberObject(0)]*3),
        NameObject('/H'):NameObject('/N'),NameObject('/A'):action,
        NameObject('/Contents'):TextStringObject('음성 재생 · 데스크톱 PDF 뷰어에서 재생')})
    annotations=page.get('/Annots',ArrayObject())
    if hasattr(annotations,'get_object'): annotations=annotations.get_object()
    annotations.append(writer._add_object(annot)); page[NameObject('/Annots')]=annotations
    writer.add_attachment('family-call.m4a',audio.read_bytes())
    writer.add_metadata({'/Title':'명문가 발표자료','/Subject':'음성 포함 발표자료 · 내장 음성은 지원하는 데스크톱 PDF 뷰어에서 재생'})
    with destination.open('wb') as stream: writer.write(stream)
    print(json.dumps({'pdf_pages':len(writer.pages),'sound_samples':len(raw_pcm)//2,
                      'seconds':len(raw_pcm)/44100,'self_contained':True}))

def pdf_from_renders(folder, destination):
    from reportlab.pdfgen import canvas
    pages=sorted(folder.glob('slide-*.png'))
    if not pages: raise ValueError('No rendered slides')
    pdf=canvas.Canvas(str(destination),pagesize=(720,405),pageCompression=1,pdfVersion=(1,7))
    pdf.setTitle('명문가 발표자료')
    for image in pages:
        pdf.drawImage(str(image),0,0,width=720,height=405,mask='auto')
        pdf.showPage()
    pdf.save()
    print(json.dumps({'rendered_pdf_pages':len(pages),'pixel_size':[1920,1080]}))

if __name__=='__main__':
    if sys.argv[1]=='pptx': embed_pptx(Path(sys.argv[2]),Path(sys.argv[3]))
    elif sys.argv[1]=='pdf': embed_pdf(*(Path(p) for p in sys.argv[2:6]))
    elif sys.argv[1]=='pdf-pages': pdf_from_renders(Path(sys.argv[2]),Path(sys.argv[3]))
    else: raise ValueError('Expected pptx or pdf')
