import fs from 'node:fs/promises';
import path from 'node:path';
import {createRequire} from 'node:module';
import {pathToFileURL,fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';

const ROOT=process.env.ROYAL_FAMILY_WORKSPACE||path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const BUNDLE=process.env.CODEX_ARTIFACT_RUNTIME||'C:/Users/dldnj/.cache/codex-runtimes/codex-primary-runtime/dependencies';
const MODULES=path.join(BUNDLE,'node/node_modules');
process.env.RUNTIME_NODE_MODULES=MODULES;
const req=createRequire(path.join(MODULES,'__royal-v6__.cjs'));
const {Presentation,PresentationFile,FileBlob}=await import(pathToFileURL(req.resolve('@oai/artifact-tool')).href);
const {GlobalFonts}=req('@napi-rs/canvas');
for(const [f,n] of [['NotoSansKR-VF.ttf','Noto Sans KR'],['NotoSerifKR-VF.ttf','Noto Serif KR'],['calibril.ttf','Calibri Light']])GlobalFonts.registerFromPath('C:/Windows/Fonts/'+f,n);
const SKILL='C:/Users/dldnj/.codex/plugins/cache/openai-primary-runtime/presentations/26.1007.11041/skills/presentations';
const {finalizePresentation,applyPresentationChartFont}=await import(pathToFileURL(path.join(SKILL,'container_tools/artifact_tool_utils.mjs')).href);
const PPT=path.join(ROOT,'ppt');
const BUILD=path.join(ROOT,'.codex/ppt-build-20261009-teal');
const INPUT=process.env.PPT_INPUT||path.join(PPT,'명문가_발표초안_v5_Cloud.pptx');
const REV=process.env.PPT_REVISION||'v6_청록';
const FINAL=path.join(PPT,`명문가_발표초안_${REV}.pptx`);
const PREVIEW=path.join(PPT,`preview-${REV.split('_')[0]}`);
await fs.mkdir(BUILD,{recursive:true});await fs.mkdir(PREVIEW,{recursive:true});
const inputHash=createHash('sha256').update(await fs.readFile(INPUT)).digest('hex');
const market=JSON.parse(await fs.readFile(path.join(PPT,'data/market-estimate-v6.json'),'utf8'));
const years=JSON.parse(await fs.readFile(path.join(PPT,'data/precedent-year-search-20261009.json'),'utf8'));
const C={paper:'#F5F2E9',teal:'#183E35',blue:'#567D89',sage:'#729690',muted:'#687568',rust:'#A85437',pale:'#A8C0B9',grid:'#D9DEDA'};
const SANS='Noto Sans KR',NUM='Calibri Light',SERIF='Noto Serif KR';
const old=await PresentationFile.importPptx(await FileBlob.load(INPUT));
const proto=old.toProto();
if(proto.slides.length!==28)throw Error('Expected the user-edited 28-slide v5; inspect changed source before using this builder');
const content=e=>(e.paragraphs||[]).flatMap(p=>(p.runs||[]).map(r=>r.text||'')).join('');
const pxBBox=(e,x,y,w,h)=>e.bbox={xEmu:Math.round(x*9525),yEmu:Math.round(y*9525),widthEmu:Math.round(w*9525),heightEmu:Math.round(h*9525)};
const isFooter=e=>(e.bbox?.yEmu||0)/9525>=502;
const colorMap={'FFFFFF':C.paper,'6BBADD':C.teal,'EEAD2A':C.blue,'ABCA48':C.sage,'D05258':C.rust,'626262':C.teal,'1B1B1B':C.teal};
function recolor(v,dark=false){
 if(!v||typeof v!=='object')return;
 if(v.type===1&&typeof v.value==='string'){
   const rgb=v.value.toUpperCase();
   if(colorMap[rgb])v.value=(dark&&rgb==='6BBADD'?C.pale:colorMap[rgb]).slice(1);
 }
 for(const [k,x] of Object.entries(v))if(!['notesSlide','contentReferences','images'].includes(k)){
   if(Array.isArray(x))x.forEach(o=>recolor(o,dark));else if(x&&typeof x==='object')recolor(x,dark);
 }
}
for(const s of proto.slides){
 const bg=s.background?.color?.value||s.background?.fill?.color?.value||'';
 const dark=['183E35','1B1B1B','6BBADD'].includes(bg.toUpperCase());
 recolor(s,dark);
 if(dark&&s.background?.color)s.background.color.value=C.teal.slice(1);
 if(dark&&s.background?.fill?.color)s.background.fill.color.value=C.teal.slice(1);
 for(const e of s.elements){
  if(!content(e))continue;
  const footer=isFooter(e);
  const status=(e.bbox?.yEmu||0)/9525>=470&&!footer;
  if(dark&&footer){for(const p of e.paragraphs||[])for(const r of p.runs||[])if(r.textStyle?.fill?.color)r.textStyle.fill.color.value=C.paper.slice(1);}
  if(status&&!dark){for(const p of e.paragraphs||[])for(const r of p.runs||[])if(r.textStyle?.fill?.color)r.textStyle.fill.color.value=C.muted.slice(1);}
 }
}
// Keep user-owned wording and media. Only the photo placement changes slide 3.
const family=proto.slides[2];
const familyTitle=family.elements.find(e=>content(e).includes('전주이씨'));
if(!familyTitle)throw Error('Family introduction anchor missing');
pxBBox(familyTitle,46,27,868,61);
for(const p of familyTitle.paragraphs||[])for(const r of p.runs||[])r.textStyle={...r.textStyle,fontSize:2550,bold:true};
familyTitle.textStyle={...familyTitle.textStyle,fontSize:2550,bold:true};

// Reuse the user's three native circles as an inclusion diagram.
const tam=proto.slides[21];
const circles=tam.elements.filter(e=>e.shape?.geometry===35);
if(circles.length!==3)throw Error('Expected three user-authored TAM inclusion circles');
tam.elements=tam.elements.filter(isFooter);
for(const [i,e] of circles.entries()){
 const [x,y,z]=[[72,88,397],[138,220,265],[193.5,331,154]][i];
 pxBBox(e,x,y,z,z);
 e.shape.fill={type:1,color:{type:1,value:C.paper.slice(1)},gradientStops:[],pictureEffects:[]};
 e.shape.line.fill.color={type:1,value:[C.teal,C.blue,C.rust][i].slice(1)};
 tam.elements.push(e);
}
const pageArt=structuredClone(proto.slides[0].elements.filter(isFooter));
// The old forecast workbook was a generated literal snapshot. Rebuild this chart
// from the newly estimated SAM and complete model data instead of retaining stale references.
const replacedChartIds=new Set(proto.slides[22].elements.filter(e=>e.chartReference).map(e=>e.chartReference.id));
proto.slides[22].elements=proto.slides[22].elements.filter(e=>!e.chartReference);
proto.charts=proto.charts.filter(c=>!replacedChartIds.has(c.id));
let deck=Presentation.load(proto);
const textLog=[];
function text(s,value,x,y,w,h,size=28,o={}){
 const sh=s.shapes.add({geometry:'textbox',name:value.replaceAll('\n',' '),position:{left:x,top:y,width:w,height:h},fill:'none',line:{fill:'none',width:0}});
 sh.text=value;sh.text.style={typeface:o.numeric?NUM:SANS,fontSize:size,bold:o.bold??false,color:o.color||C.teal,alignment:o.align||'left',verticalAlignment:'top',wrap:'none',autoFit:'none',insets:0};
 textLog.push({slideId:s.id,text:value});return sh;
}
const fsld=deck.slides.items[2];
fsld.images.add({blob:await fs.readFile(path.join(PPT,'assets/family-land-chat.png')),contentType:'image/png',alt:'사용자 제공 가족 대화와 공주 태봉동 지번 지도 캡처',fit:'contain',position:{left:330,top:100,width:300,height:425}});
fsld.speakerNotes.text+='\n사용자 제공 가족 대화·지도 캡처. 대화 속 주장과 지도 표시의 소유권, 매각 면적은 별도 검증하지 않았다. 원본 비율 유지. 출처: ppt/assets/family-land-chat.png';

const t=deck.slides.items[21];
text(t,'시장 규모',46,25,868,58,38,{align:'center'});
for(const [label,y,color] of [['TAM',108,C.teal],['SAM',236,C.blue],['SOM',351,C.rust]])text(t,label,173,y,195,42,label==='SOM'?29:31,{numeric:true,color,align:'center'});
text(t,'등록 종중',116,160,309,34,21,{align:'center'});
text(t,'전담 관리 종중 제외',149,284,243,32,18,{align:'center'});
text(t,'3년 내 SAM 1%',198,405,146,30,17,{align:'center'});
text(t,'연 구독매출, 억원',542,91,340,35,18,{color:C.muted});
const items=[{n:market.TAM.display_eok,count:market.TAM.clans,y:124,color:C.teal},{n:market.SAM.display_eok,count:market.SAM.clans,y:260,color:C.blue},{n:market.SOM.display_eok,count:market.SOM.clans,y:392,color:C.rust}];
for(const v of items){text(t,v.n.toLocaleString('en-US',{minimumFractionDigits:2,maximumFractionDigits:2}),538,v.y,355,66,53,{numeric:true,color:v.color});text(t,v.count.toLocaleString('en-US')+'개',542,v.y+66,335,34,22,{color:C.muted});}
text(t,'연 50만원, 사업형 20% 제외, 3년 내 1% 확보 가정. 원은 포함 관계이며 면적 비례 아님',49,486,867,24,14,{color:C.muted});
t.speakerNotes.text=[market.market_boundary,market.professional_management_exclusion.definition,market.professional_management_exclusion.basis,market.diagram_boundary,market.rounding,'출처: '+market.registered_clans.source,'출처: '+market.subscription_assumption.benchmark,'계산: ppt/data/market-estimate-v6.json'].join('\n');

// Create a new native forecast chart and a literal workbook snapshot from the current model.
const forecast=deck.slides.items[22];
const forecastChart=forecast.charts.add('bar',{
 position:{left:103,top:135,width:735,height:313},
 categories:market.SOM_scenarios.map(q=>`${q.penetration_assumption*100}%  ${q.clans.toLocaleString('en-US')}개`),
 series:[{name:'연환산 매출',values:market.SOM_scenarios.map(q=>q.display_eok),points:[{idx:0,fill:C.teal},{idx:1,fill:C.sage},{idx:2,fill:C.rust}]}],
 barOptions:{direction:'bar',grouping:'clustered',gapWidth:68,varyColors:true},hasLegend:false,
 yAxis:{visible:true,min:0,max:25,majorUnit:5,numberFormatCode:'0',title:'억원 / 년',majorGridlines:{fill:C.grid,width:1},textStyle:{fontSize:17,fill:C.muted}},
 xAxis:{visible:true,majorGridlines:null,textStyle:{fontSize:20,fill:C.teal},line:{fill:'none',width:0}},
 dataLabels:{showValue:true,position:'outEnd',numberFormatCode:'0.00',textStyle:{fontSize:25,fill:C.teal}},
 chartFill:C.paper,plotAreaFill:C.paper,
});
applyPresentationChartFont(forecastChart,{fontFamily:SANS});
forecast.speakerNotes.text=[market.market_boundary,JSON.stringify(market.SOM_scenarios),'SAM: 전담 관리 종중 20% 제외 가정. 구독료 연 50만원. 3년 내 확보율은 실제 구매율이 아닌 시나리오.','계산: ppt/data/market-estimate-v6.json','출처: '+market.registered_clans.source].join('\n');

// Add one factual graph after the public-search evidence.
const g=deck.slides.add();g.background.fill=C.paper;
text(g,'종중 관련 판례, 선고연도별',46,25,868,60,38,{align:'center'});
text(g,'검색 건수',66,112,245,29,18,{color:C.muted});
const yearChart=g.charts.add('bar',{
 position:{left:61,top:143,width:831,height:303},categories:years.rows.map(q=>String(q.year)),
 series:[{name:'공개 판례 검색 건수',values:years.rows.map(q=>q.entries),fill:C.blue}],
 barOptions:{direction:'column',grouping:'clustered',gapWidth:112},hasLegend:false,
 xAxis:{visible:true,majorGridlines:null,textStyle:{fontSize:22,fill:C.teal},line:{fill:C.grid,width:1}},
 yAxis:{visible:true,min:0,max:40,majorUnit:10,numberFormatCode:'0',majorGridlines:{fill:C.grid,width:1},textStyle:{fontSize:16,fill:C.muted}},
 dataLabels:{showValue:true,position:'outEnd',numberFormatCode:'0',textStyle:{fontSize:29,fill:C.teal}},
 chartFill:C.paper,plotAreaFill:C.paper,
});applyPresentationChartFont(yearChart,{fontFamily:SANS});
text(g,'판례본문 “종중”, 선고일자 기준. 동일 사건의 심급·요약 포함 가능. 신규 분쟁 수와 다름',49,479,867,27,15,{color:C.muted});
g.speakerNotes.text=[years.measure,years.boundary,'조회 조건: 판례본문 종중, 법원 전체, 사건대상 전체. 각 연도 1월1일~12월31일의 선고일자. 조회일 2026-10-09.',JSON.stringify(years.rows),'출처: '+years.source,'데이터: ppt/data/precedent-year-search-20261009.json'].join('\n');

const outProto=deck.toProto();
const graph=outProto.slides.pop();
graph.elements.unshift(...pageArt);
outProto.slides.splice(7,0,graph);
outProto.slides.forEach((s,i)=>{
 s.index=i;
 for(const e of s.elements){
  const box=e.bbox;
  if(box&&box.yEmu/9525>=502&&box.xEmu/9525>=50&&box.xEmu/9525<=100&&box.widthEmu/9525<=45&&/^\d+$/.test(content(e))){
   e.name=String(i+1);const p=e.paragraphs[0];p.runs[0].text=String(i+1);p.runs.splice(1);
  }
 }
});
deck=Presentation.load(outProto);
await fs.writeFile(path.join(BUILD,'authored-v6-proto.json'),JSON.stringify(outProto));
const candidate=path.join(BUILD,`candidate-${REV}.pptx`);
await (await PresentationFile.exportPptx(deck)).save(candidate);
console.log('DRAFT_EXPORTED '+candidate);
const result=await finalizePresentation({workspaceDir:ROOT,candidatePath:candidate,finalPath:FINAL,pythonExecutable:path.join(BUNDLE,'python/python.exe'),integrityValidatorPath:path.join(SKILL,'container_tools/inspect_presentation_package_integrity.py'),layoutValidatorPath:path.join(SKILL,'container_tools/inspect_presentation_layout_geometry.py'),layoutArgs:['--expected-slide-size-emu','9144000,5143500','--validate-heading-fit','--validate-bullet-geometry'],requiredNativeChartOwnerSlides:[8,24],materializeLiteralChartWorkbooks:true,fontPolicy:{basis:'reference',families:[SANS,SERIF,NUM],referencePath:INPUT,referenceSha256:inputHash},verifyArtifactToolImport:true,receiptPath:path.join(BUILD,`validation-${REV}.json`)});
console.log('FINALIZED '+JSON.stringify({path:result.finalPath,slides:result.packageIntegrity.slide_count,layoutFindings:result.presentationLayout.findingCount,sha256:result.finalSha256}));
const finalDeck=await PresentationFile.importPptx(await FileBlob.load(FINAL));
for(const [i,s] of finalDeck.slides.items.entries()){
 await fs.writeFile(path.join(PREVIEW,`slide-${String(i+1).padStart(2,'0')}.png`),new Uint8Array(await (await finalDeck.export({slide:s,format:'png',scale:1.333333})).arrayBuffer()));
 console.log('RENDERED '+(i+1));
}
await fs.writeFile(path.join(BUILD,'revision-v6-receipt.json'),JSON.stringify({source:INPUT,sourceSha256:inputHash,final:FINAL,sha256:result.finalSha256,nativeCharts:[8,24],familyPhotoSlide:3,marketDiagramSlide:23,sourceSlideCount:28,finalSlideCount:29,palette:C},null,2));
await fs.writeFile(path.join(BUILD,'new-copy-v6.json'),JSON.stringify(textLog,null,2));
console.log('DONE '+FINAL);
process.exit(0);
