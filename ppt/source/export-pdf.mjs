import fs from 'node:fs/promises';
import path from 'node:path';
import {createRequire} from 'node:module';
import {fileURLToPath,pathToFileURL} from 'node:url';
import {execFileSync} from 'node:child_process';

const ROOT=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const BUNDLE=process.env.CODEX_ARTIFACT_RUNTIME||'C:/Users/dldnj/.cache/codex-runtimes/codex-primary-runtime/dependencies';
const MODULES=path.join(BUNDLE,'node/node_modules');process.env.RUNTIME_NODE_MODULES=MODULES;
const req=createRequire(path.join(MODULES,'__royal-pdf__.cjs'));
const {GlobalFonts}=req('@napi-rs/canvas');
GlobalFonts.registerFromPath('C:/Windows/Fonts/NotoSerifKR-VF.ttf','Noto Serif KR');
for(const [f,n]of [['Pretendard-Regular.ttf','Pretendard'],['Pretendard-Bold.ttf','Pretendard'],['SUIT-Regular.ttf','SUIT'],['SUIT-Bold.ttf','SUIT'],['SUIT-SemiBold.ttf','SUIT SemiBold']])GlobalFonts.registerFromPath(path.join(ROOT,'ppt/assets/fonts',f),n);
const {PresentationFile,FileBlob}=await import(pathToFileURL(req.resolve('@oai/artifact-tool')).href);
const source=process.argv[2]||path.join(ROOT,'ppt/명문가_발표초안_v16_IR.pptx');
const output=process.argv[3]||path.join(ROOT,'ppt/명문가_발표초안_v16_IR.pdf');
const build=process.argv[4]||path.join(ROOT,'.codex/ppt-build-20261009-v16');
await fs.mkdir(build,{recursive:true});
const candidate=path.join(build,'presentation-before-audio.pdf');
const deck=await PresentationFile.importPptx(await FileBlob.load(source));
let route='artifact-vector-pdf';
try{
 await fs.writeFile(candidate,new Uint8Array(await(await deck.export({format:'pdf'})).arrayBuffer()));
}catch(error){
 if(!String(error?.message).includes('#standard-fonts/'))throw error;
 // The bundled PDF exporter lacks its standard-font resource mapping. Preserve
 // the verified slide appearance with full-HD renders; do not patch the runtime.
 route='full-hd-render-pdf';
 const rendered=path.join(build,'pdf-source-renders');await fs.mkdir(rendered,{recursive:true});
 for(const [i,slide]of deck.slides.items.entries()){
  await fs.writeFile(path.join(rendered,`slide-${String(i+1).padStart(2,'0')}.png`),new Uint8Array(await(await deck.export({slide,format:'png',scale:2})).arrayBuffer()));
 }
 execFileSync(path.join(BUNDLE,'python/python.exe'),[path.join(ROOT,'ppt/source/embed-media.py'),'pdf-pages',rendered,candidate],{stdio:'inherit'});
}
execFileSync(path.join(BUNDLE,'python/python.exe'),[path.join(ROOT,'ppt/source/embed-media.py'),'pdf',candidate,output,path.join(ROOT,'ppt/assets/ir-v14/family-call.m4a'),path.join(ROOT,'ppt/assets/ir-v14/family-call.wav')],{stdio:'inherit'});
await fs.writeFile(path.join(build,'pdf-export-route.json'),JSON.stringify({route,source,output,slides:deck.slides.items.length},null,2));
console.log('PDF_READY '+output);
process.exit(0);
