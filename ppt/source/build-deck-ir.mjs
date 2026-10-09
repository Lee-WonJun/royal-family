import fs from 'node:fs/promises';
import path from 'node:path';
import {createRequire} from 'node:module';
import {fileURLToPath,pathToFileURL} from 'node:url';
import {createHash} from 'node:crypto';

const ROOT=process.env.ROYAL_FAMILY_WORKSPACE||path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const BUNDLE=process.env.CODEX_ARTIFACT_RUNTIME||'C:/Users/dldnj/.cache/codex-runtimes/codex-primary-runtime/dependencies';
const MODULES=path.join(BUNDLE,'node/node_modules'); process.env.RUNTIME_NODE_MODULES=MODULES;
const req=createRequire(path.join(MODULES,'__royal-ir__.cjs'));
const {Presentation,PresentationFile,FileBlob}=await import(pathToFileURL(req.resolve('@oai/artifact-tool')).href);
const sharp=req('sharp'),{GlobalFonts}=req('@napi-rs/canvas');
GlobalFonts.registerFromPath('C:/Windows/Fonts/NotoSerifKR-VF.ttf','Noto Serif KR');
for(const [f,n] of [['Pretendard-Regular.ttf','Pretendard'],['Pretendard-Bold.ttf','Pretendard'],['Pretendard-SemiBold.ttf','Pretendard SemiBold']])GlobalFonts.registerFromPath(path.join(ROOT,'ppt/assets/fonts',f),n);
const SKILL='C:/Users/dldnj/.codex/plugins/cache/openai-primary-runtime/presentations/26.1007.11041/skills/presentations';
const {finalizePresentation,applyPresentationChartFont}=await import(pathToFileURL(path.join(SKILL,'container_tools/artifact_tool_utils.mjs')).href);
const PPT=path.join(ROOT,'ppt'),ASSETS=path.join(PPT,'assets'),BUILD=path.join(ROOT,'.codex/ppt-build-20261009-v11');
const REV=process.env.PPT_REVISION||'v11_IR',FINAL=process.env.PPT_FINAL_PATH||path.join(PPT,`명문가_발표초안_${REV}.pptx`);
const PREVIEW=process.env.PPT_PREVIEW_DIR||path.join(PPT,'preview-v11');
await fs.mkdir(BUILD,{recursive:true});await fs.mkdir(PREVIEW,{recursive:true});
const market=JSON.parse(await fs.readFile(path.join(PPT,'data/market-estimate-v6.json'),'utf8'));
const years=JSON.parse(await fs.readFile(path.join(PPT,'data/precedent-year-search-20261009.json'),'utf8'));
const evidence=JSON.parse(await fs.readFile(path.join(PPT,'data/implementation-evidence-v7.json'),'utf8'));
const parcel=JSON.parse(await fs.readFile(path.join(PPT,'data/parcel-source-v7.json'),'utf8'));
const C={paper:'#F5F2E9',teal:'#183E35',navy:'#173F49',blue:'#567D89',sage:'#729690',muted:'#687568',rust:'#A85437',pale:'#DCE7E2',grid:'#D2DCD6',white:'#FFFFFF',light:'#BDCFCA'};
const SANS='Pretendard',HEAD=SANS,NUM=SANS,SERIF='Noto Serif KR';
const p=Presentation.create({slideSize:{width:960,height:540}}),copy=[],chartOwners=[],devicePlacements=[];
const src=s=>path.join(ROOT,s).replaceAll('\\','/');
const PLAN=src('rawdata/PPTPlan/script.md'),IMPL=src('ppt/data/implementation-evidence-v7.json');
const PRD=src('docs/prd/hackathon-prd.md'),ARCH=src('docs/architecture/system-design.md');
const n=number=>number.toLocaleString('en-US');
function text(s,value,x,y,w,h,size=28,o={}){
 const q=s.shapes.add({geometry:'textbox',name:value.replaceAll('\n',' '),position:{left:x,top:y,width:w,height:h},fill:'none',line:{fill:'none',width:0}});
 q.text=value;q.text.style={typeface:o.font||(o.numeric?NUM:o.bold?HEAD:SANS),fontSize:size,bold:o.bold??(o.numeric&&size>=50),color:o.color||C.teal,alignment:o.align||'left',verticalAlignment:'top',wrap:'none',autoFit:'none',insets:0};
 if(o.link)q.text.get(value).link={uri:o.link,isExternal:true};
 copy.push({slide:p.slides.items.length,text:value,size});return q;
}
function shape(s,x,y,w,h,fill='none',stroke='none',width=0,geometry='rect'){
 return s.shapes.add({geometry,position:{left:x,top:y,width:w,height:h},fill,line:{fill:stroke,width}});
}
function line(s,x,y,w,color=C.grid){return shape(s,x,y,w,0,'none',color,1,'line');}
function arrow(s,x,y,w=40,color=C.blue){return shape(s,x,y,w,14,color,'none',0,'rightArrow');}
function note(s,value,color=C.muted){text(s,value,52,486,838,29,14,{color});}
function slide({title,bg=C.paper,notes='',sources=[PLAN],foot,chapter,number=true}={}){
 const s=p.slides.add();s.background.fill=bg;
 const dark=[C.teal,C.navy].includes(bg),fg=dark?C.paper:C.teal;
 if(chapter)text(s,chapter,53,27,660,23,14,{color:dark?C.light:C.muted});
 if(title)text(s,title,50,66,866,65,40,{bold:true,color:fg});
 if(number)text(s,String(p.slides.items.length).padStart(2,'0'),896,502,31,22,13,{numeric:true,color:dark?C.light:C.muted,align:'right'});
 s.speakerNotes.text=[notes,foot,...sources.map(x=>'출처: '+x)].filter(Boolean).join('\n');return s;
}
async function image(s,f,x,y,w,h,alt,fit='contain'){
 const file=path.isAbsolute(f)?f:path.join(ASSETS,f);
 const contentType=file.toLowerCase().endsWith('.jpg')?'image/jpeg':'image/png';
 return s.images.add({blob:await fs.readFile(file),contentType,alt,fit,position:{left:x,top:y,width:w,height:h}});
}
const iconCache=new Map();
async function icon(s,name,x,y,size,color=C.teal){
 const key=name+color;
 if(!iconCache.has(key)){
  const svg=(await fs.readFile(path.join(ASSETS,'icons',name+'.svg'),'utf8')).replaceAll('stroke="currentColor"',`stroke="${color}"`);
  iconCache.set(key,await sharp(Buffer.from(svg),{density:768}).resize(640,640).png().toBuffer());
 }
 return s.images.add({blob:iconCache.get(key),contentType:'image/png',alt:'Lucide '+name,fit:'contain',position:{left:x,top:y,width:size,height:size}});
}
// Original template device artwork, plus separate replaceable screen images.
// Source: Premium Cloud Widescreen Multicolored, slides 306 and 312.
async function laptop(s,screen,x,y,w){
 const k=w/1275,h=681*k;
 await image(s,'ir/template-macbook.png',x,y,w,h,'사용자 템플릿의 맥북 목업');
 const box={x:x+228*k,y:y+56*k,w:819*k,h:514*k};
 shape(s,box.x,box.y,box.w,box.h,C.white);
 const meta=await sharp(path.join(ASSETS,'ir-v11',screen)).metadata();
 if(meta.width*514!==meta.height*819)throw Error('Macbook capture ratio: '+screen);
 const placed=await image(s,'ir-v11/'+screen,box.x,box.y,box.w,box.h,'명문가 실제 구현 화면: '+screen,'cover');
 devicePlacements.push({slide:p.slides.items.length,imageId:placed.id,screen,box,kind:'macbook'});
}
async function phone(s,screen,x,y,h){
 const k=h/1080,w=592*k;
 await image(s,'ir/template-phone.png',x,y,w,h,'사용자 템플릿의 아이폰 목업');
 const box={x:x+76*k,y:y+116*k,w:438*k,h:775*k};
 shape(s,box.x,box.y,box.w,box.h,C.white);
 const meta=await sharp(path.join(ASSETS,'ir-v11',screen)).metadata();
 if(meta.width*775!==meta.height*438)throw Error('Phone capture ratio: '+screen);
 const placed=await image(s,'ir-v11/'+screen,box.x,box.y,box.w,box.h,'명문가 실제 휴대폰 화면: '+screen,'cover');
 devicePlacements.push({slide:p.slides.items.length,imageId:placed.id,screen,box,kind:'phone'});
}
function chart(s,type,opts){const q=s.charts.add(type,opts);applyPresentationChartFont(q,{fontFamily:SANS});chartOwners.push(p.slides.items.length);return q;}

// Keep the user's opening, experience and brand reveal.
{
 const s=slide({bg:C.paper,notes:'발표자가 아버지와의 통화 녹음을 외부 플레이어로 재생한다. PPT에는 오디오를 삽입하지 않았다.',sources:[PLAN,src('rawdata/TTS/02_전사_보완본.md')],number:false});
 await icon(s,'phone',416,86,128,C.blue);
 text(s,'아버지와의 통화',52,279,856,81,55,{bold:true,align:'center'});
 text(s,'통화 녹음',52,403,856,38,22,{color:C.muted,align:'center'});
}
{
 const s=slide({bg:C.teal,notes:'청중에게 자신의 종중을 떠올릴 시간을 준다.',number:false});
 text(s,'여러분의 종중은',66,128,828,87,65,{color:C.paper,bold:true});
 text(s,'안녕',65,247,179,116,84,{color:'#D05258',bold:true});
 text(s,'하십니까?',244,247,625,116,84,{color:C.paper,bold:true});
}
{
 const s=slide({notes:'사용자 제공 가족 대화와 지번 지도 원본을 비율 그대로 사용한다. 대화 속 주장이나 지도 표시는 소유권과 매각 범위의 검증 결과가 아니다.',sources:[src('ppt/assets/family-land-chat-source.json')],number:false});
 text(s,'전주이씨',51,24,858,35,24,{align:'center'});
 text(s,'임영대군파종중',51,60,858,50,35,{bold:true,align:'center'});
 await image(s,'family-land-chat.png',330,118,300,411,'전주이씨 임영대군파종중 아래에 놓은 사용자 제공 사진');
}
{
 const s=slide({title:'종중',chapter:'우리 가족의 일',notes:'가문의 뿌리, 공동재산, 기록, 종원을 연결한 설명용 개념도다. 특정 지목이나 산림에 한정하지 않는다.'});
 const a=[['git-branch','뿌리'],['landmark','공동재산'],['files','기록'],['users-round','종원']];
 for(const [i,[ic,t]]of a.entries()){const x=61+i*222;await icon(s,ic,x+51,194,100,i===1?C.blue:C.teal);text(s,t,x,350,202,48,31,{align:'center',bold:true});}
}
{
 const s=slide({title:'다툼과 소송의 온상',chapter:'공개 보도 사례',sources:['https://www.jmbc.co.kr/news/view/68182'],foot:'전주MBC 2026.09.22 보도. 발표자 가족의 사건과는 별개의 보도 사례.',notes:'보도는 가짜 종중 대표와 토지 거래, 약 4년에 걸친 법정 다툼을 다룬다. 뉴스 화면은 실제 보도 캡처다. 기간은 보도 문맥의 수치이며 모든 종중 분쟁의 평균이 아니다.'});
 await image(s,'news-jmbc.jpg',52,163,594,292,'전주MBC 실제 보도 화면');
 text(s,'약',688,168,215,34,23,{color:C.muted});text(s,'4',679,190,150,152,131,{numeric:true});text(s,'년',799,268,95,52,34);
 text(s,'법정 다툼',687,372,225,41,28,{bold:true});
}
{
 const s=slide({title:'지위·임대차·대표권',chapter:'문제가 생기는 곳',sources:['https://www.bubb.co.kr/news/articleView.html?idxno=4550','https://www.cctoday.co.kr/news/articleView.html?idxno=2233033','https://magazine.hankyung.com/business/article/202607168245b'],notes:'세 매체의 실제 제목 캡처다. 각 보도는 별개 사건이고 당사자 주장과 법원 판단을 구분한다.'});
 const rows=[['종손 지위','news-bubb-title.jpg','법치뉴스 2026.04.28'],['임대차','news-cctoday-title.jpg','충청투데이 2026.07.10'],['대표권','news-hankyung-title.jpg','한경BUSINESS 2026.07.24']];
 for(const [i,[label,img,date]]of rows.entries()){const y=150+i*110;text(s,label,52,y+9,197,49,29,{bold:true,color:C.teal});await image(s,img,249,y,654,67,'실제 원문 기사 제목');text(s,date,253,y+72,649,25,15,{color:C.muted});if(i<2)line(s,52,y+100,850);}
}
{
 const s=slide({title:'종중 관련 공개 판례',chapter:'공개 판례 검색',sources:[market.precedent_search.source,src('ppt/data/market-estimate-v6.json')],notes:JSON.stringify(market.precedent_search,null,2)});
 text(s,'1,416',57,180,625,172,134,{numeric:true});text(s,'건',646,267,95,72,44);
 await icon(s,'file-search',763,218,123,C.blue);
 text(s,'기간 제한 없는 전체 검색',64,392,744,47,26,{color:C.muted});
 text(s,'2026.10.09 조회',66,454,744,32,19,{color:C.muted});
}
{
 const s=slide({title:'결국, 누군가 이어받는 일',notes:'과거의 유산, 오늘의 일상, 미래의 문제라는 발표자의 흐름을 설명한다.'});
 const r=[['trees','과거의 유산'],['notebook-text','오늘의 일상'],['users-round','미래의 문제']];
 for(const [i,[ic,t]]of r.entries()){let x=57+i*297;await icon(s,ic,x+76,176,96,i===1?C.blue:C.teal);text(s,t,x,322,259,49,30,{bold:true,align:'center'});if(i<2)arrow(s,x+258,217,35);}
}
{
 const s=slide({bg:C.teal,notes:'사용자 본인의 종중 관련 창업 실패 경험을 말하는 장면. 매출, 기간, 성과 수치는 추가하지 않는다.',number:false});
 text(s,'한 번',69,124,820,111,81,{color:C.paper,bold:true});text(s,'망했습니다',68,249,835,119,84,{color:C.paper,bold:true});
}
{
 const s=slide({notes:'다음 기능은 목표와 시연 구현을 구분해서 소개한다. 배경은 개념 일러스트이며 제품 실행 결과가 아니다.',number:false});
 await image(s,'records-ai.png',0,0,960,540,'문서와 녹음기를 그린 개념 일러스트');
 text(s,'AI ERA',58,121,417,114,79,{numeric:true,bold:true});
 text(s,'녹음',64,285,361,42,29,{bold:true});text(s,'문서 초안',64,338,361,42,29,{bold:true});text(s,'자료 비교',64,391,361,42,29,{bold:true});
}
{
 const s=slide({notes:'소나무와 사당 배경은 특정 실제 장소가 아닌 생성 일러스트다.',number:false});
 await image(s,'clan-pine.png',0,0,960,540,'명문가 브랜드 개념 일러스트');
 text(s,'우리 가문은',58,119,377,43,27);text(s,'명문가',54,181,442,126,91,{font:SERIF,bold:true});text(s,'종중 운영의 모든것',59,332,424,43,27);
}

// 12–18: device mockups and large typography, one product scene per slide.
{
 const s=slide({bg:C.paper,chapter:'종중 홈',notes:'현재 구현된 앱 홈 화면. 가상 종원과 예시 업무를 사용하는 시연이다. 목업과 화면은 각각 교체 가능한 이미지다.',sources:[IMPL,src('ppt/assets/ir-v11/ui-home.jpg')],foot:'실제 구현 화면 · 예시 데이터'});
 text(s,'오늘\n확인할 일.',48,116,420,187,62,{bold:true});
 text(s,'총무의 첫 화면',53,364,342,42,23,{color:C.blue});
 await laptop(s,'ui-home.jpg',330,139,626);
}
{
 const s=slide({chapter:'설립 준비',bg:'#E5EDEA',notes:'보유 서류와 부족한 자료를 확인하고, 조건에 맞는 가상 법무사 후보를 비교하는 흐름이다. 외부 전문가 접수는 아직 연결하지 않았다.',sources:[PRD,IMPL,src('ppt/assets/ir-v11/ui-preparation.jpg')],foot:'준비 서류 화면 구현 · 전문가 후보는 가상 데이터'});
 await laptop(s,'ui-preparation.jpg',5,135,631);
 text(s,'첫 서류부터\n빠짐없이.',579,96,362,159,51,{bold:true});
 text(s,'보유 서류\n추가 확인\n법무사 후보',634,308,285,121,25,{color:C.blue});
}
{
 const s=slide({chapter:'회의 녹음',bg:C.navy,notes:'실제 앱의 휴대폰 회의록 화면이다. 고정된 시연 전사문을 표시하며 Whisper 어댑터는 구현됐으며 이 화면은 해당 연결 이전에 확보한 고정 시연 전사문이다. 녹음, 불명확한 항목 확인, 회의록 작성이라는 목표를 보여준다.',sources:[IMPL,src('ppt/assets/ir-v11/ui-records-phone.jpg')],foot:'시연 전사문 캡처. Whisper 어댑터 구현과 실제 호출 검증은 별도.'});
 text(s,'말한 내용을\n회의록으로.',54,115,568,191,62,{bold:true,color:C.paper});
 await icon(s,'mic',62,369,51,C.light);text(s,'녹음에서 검토까지',135,381,435,39,24,{color:C.light});
 await phone(s,'ui-records-phone.jpg',650,50,459);
}
{
 const s=slide({chapter:'문서 검색과 초안',notes:'기존 문서의 키워드 검색, 초안·개정·버전 이력·검토 기록을 보여준다. 현재 화면의 초안은 시연 데이터다. 별도로 Responses·File Search 서버 어댑터가 구현됐다. 실제 API 검증 결과와 구분한다.',sources:[IMPL,src('ppt/assets/ir-v11/ui-draft.jpg')],foot:'문서·버전·검토 화면. Responses·File Search 서버 어댑터 코드와 실제 호출 검증은 별도.'});
 text(s,'지난 회의도, 이번 초안도.',51,68,875,79,49,{bold:true});
 await laptop(s,'ui-draft.jpg',51,155,594);
 text(s,'원문',765,229,161,48,29,{bold:true});text(s,'버전',765,303,161,48,29,{bold:true});text(s,'초안',765,377,161,48,29,{bold:true,color:C.blue});
}
{
 const s=slide({chapter:'총회와 동의',bg:'#E2ECEE',notes:'휴대폰의 실제 동의 현황 화면이다. 응답은 대상자와 특정 문서 버전에 연결한다. 현재는 관리자 시연 입력이며 실제 종원 본인 인증과 외부 발송은 연결 전이다.',sources:[IMPL,src('ppt/assets/ir-v11/ui-consent-phone.jpg')],foot:'대상자·문서 버전별 시연 응답 · 본인 인증·외부 발송은 연결 전'});
 await phone(s,'ui-consent-phone.jpg',54,64,449);
 text(s,'누가,\n어떤 안건에\n동의했는지.',367,105,560,249,57,{bold:true});
 text(s,'응답을 문서 버전과 함께',376,403,540,40,24,{color:C.blue});
}
{
 const s=slide({chapter:'재산과 등기',bg:'#E2ECEE',sources:[src('ppt/assets/ir-v11/ui-land.jpg'),src('docs/architecture/parcel-map.md'),src('ppt/data/parcel-source-v7.json')],notes:'실제 공개 필지 경계를 지도에 표시한 서비스 화면이다. 등기부 재조회·소유자 변경·알림은 가상 목업이며 실제 등기 발급이나 소유권 확인을 뜻하지 않는다. 지도 출처와 저작권 표시는 캡처 안에 보존했다.'});
 text(s,'우리 종중의 땅',50,70,861,74,52,{bold:true});
 await laptop(s,'ui-land.jpg',37,150,710);
 await icon(s,'map-pin',792,216,54,C.blue);text(s,'필지 확인',759,282,173,43,24,{bold:true});
 await icon(s,'bell-ring',792,356,54,C.blue);text(s,'변경 알림',759,424,173,43,24,{bold:true});
}
{
 const s=slide({chapter:'재산과 회계',bg:C.teal,notes:'토지·계약·회계·변경 기록을 한 업무 영역에서 관리한다. 화면은 실제 앱의 예시 회계이며 실제 금융 거래가 아니다. 토지 후속 자료 비교는 별도 규칙으로 구현됐고 등기 소유권 실조회와 외부 알림은 미연결이다.',sources:[IMPL,src('ppt/assets/ir-v11/ui-accounting.jpg')],foot:'시연 금액 · 재산 비교는 등록 자료 기준 · 실제 금융 거래 없음'});
 text(s,'들어온 돈,\n나간 돈.',52,104,522,175,59,{bold:true,color:C.paper});
 text(s,'재산 자료와 회계 기록',57,363,433,40,24,{color:C.light});
 await laptop(s,'ui-accounting.jpg',347,143,609);
}
{
 const s=slide({chapter:'법률 근거와 전문가',notes:'등록된 법령·판례 링크, 규약, 자료 누락 및 조건별 가상 전문가 후보와 상담 준비를 다룬다. 법률 판단을 자동 확정하거나 실제 전문가 매칭이 완료된다는 주장이 아니다.',sources:[IMPL,src('ppt/assets/ir-v11/ui-legal.jpg')],foot:'공식 근거 링크와 상담 준비 화면 · 후보는 가상 데이터'});
 await laptop(s,'ui-legal.jpg',5,135,630);
 text(s,'우리 가문\n법률 전문가',578,105,371,153,47,{bold:true});
 text(s,'법령과 규약\n확인할 쟁점\n전문가 후보',620,306,304,132,25,{color:C.blue});
}

// 19–21: registered population, nested inclusion diagram and scenario chart.
{
 const s=slide({title:'부동산등기용 등록 종중',chapter:'시장 모수',sources:[market.registered_clans.source,src('ppt/data/market-estimate-v6.json')],foot:'2024.6 기준, 등기법포럼 자료의 국토교통부 조회값. 등록 종중 수이며 유료 고객 수는 아님.',notes:JSON.stringify(market.registered_clans)});
 text(s,n(market.TAM.clans),48,185,636,140,105,{numeric:true});text(s,'개',615,259,79,64,36);
 text(s,'2024년 6월',55,328,552,31,19,{color:C.muted});
 const items=[['trees','임야'],['map','농지'],['map-pin','대지'],['landmark','건물']];
 for(const [i,[ic,t]]of items.entries()){const x=59+i*217;await icon(s,ic,x,390,39,C.blue);text(s,t,x+58,391,133,39,24);}
}
{
 const s=slide({title:'TAM · SAM · SOM',chapter:'연 구독매출 추정',sources:[market.registered_clans.source,market.subscription_assumption.benchmark,src('ppt/data/market-estimate-v6.json')],foot:'연 50만원, 전담 관리 종중 20% 제외, 3년 내 1% 확보 가정. 원은 포함 관계이며 면적 비례 아님.',notes:JSON.stringify(market,null,2)});
 for(const [x,y,z,c]of [[59,133,344,C.teal],[116,247,230,C.blue],[163,341,136,C.rust]])shape(s,x,y,z,z,C.paper,c,2,'ellipse');
 text(s,'TAM',147,151,168,40,28,{numeric:true,align:'center'});text(s,'등록 종중',108,201,247,35,21,{align:'center'});
 text(s,'SAM',147,267,168,37,27,{numeric:true,align:'center',color:C.blue});text(s,'전담 관리 20% 제외',126,313,213,29,17,{align:'center',color:C.blue});
 text(s,'SOM',181,363,102,35,25,{numeric:true,color:C.rust,align:'center'});text(s,'3년 내 1%',169,416,125,28,17,{color:C.rust,align:'center'});
 text(s,'연 50만원 가정',660,94,258,31,18,{color:C.muted,align:'right'});
 const data=[[market.TAM,145,C.teal],[market.SAM,267,C.blue],[market.SOM,389,C.rust]];
 for(const [r,y,col]of data){text(s,r.display_eok.toLocaleString('en-US',{minimumFractionDigits:2}),488,y,312,76,57,{numeric:true,color:col});text(s,'억원 / 년',793,y+32,133,31,20,{color:col});text(s,n(r.clans)+'개',493,y+79,409,32,20,{color:C.muted});}
}
{
 const s=slide({title:'3년 내 확보율별 매출 추정',chapter:'연 50만원 기준',sources:[src('ppt/data/market-estimate-v6.json')],foot:'SAM 189,657개, 연 50만원 가정. 3년 도달 시점의 연환산 매출이며 누적 매출·실적이 아님.',notes:JSON.stringify(market.SOM_scenarios)+'\n'+market.market_boundary});
 chart(s,'bar',{position:{left:65,top:158,width:828,height:300},categories:market.SOM_scenarios.map(q=>`${q.penetration_assumption*100}%  ${n(q.clans)}개`),series:[{name:'연환산 매출',values:market.SOM_scenarios.map(q=>q.display_eok),points:[{idx:0,fill:C.sage},{idx:1,fill:C.teal},{idx:2,fill:C.blue}]}],barOptions:{direction:'bar',grouping:'clustered',gapWidth:86,varyColors:true},hasLegend:false,yAxis:{visible:true,min:0,max:25,majorUnit:5,numberFormatCode:'0',title:'억원 / 년',majorGridlines:{fill:C.grid,width:1},textStyle:{fontSize:15,fill:C.muted}},xAxis:{visible:true,majorGridlines:null,textStyle:{fontSize:22,fill:C.teal},line:{fill:'none',width:0}},dataLabels:{showValue:true,position:'outEnd',numberFormatCode:'0.00',textStyle:{fontSize:27,fill:C.teal}},chartFill:C.paper,plotAreaFill:C.paper});
}

// Codex section opens here, after the market slides.
{
 const s=slide({title:'Codex 활용',sources:[IMPL,PRD,src('rawdata/Law/catalog.json'),src('ppt/data/parcel-source-v7.json'),src('qa/ui-acceptance-2026-10-09.md')],notes:'통화 음성 전사와 문맥 보완, PRD·종원 페르소나·업무 흐름, 화면·업무 코드·배포, 법령·판례·공개 필지 수집, 단위·PBT·독립 UI 검수를 한 페이지에 모았다. 가상 페르소나는 실제 사용자 조사 표본이 아니며 공식 자료 수집과 서비스 색인은 별도다.'});
 const core=shape(s,406,241,148,148,C.teal,'none',0,'ellipse');
 const work=[
  {x:438,y:128,d:84,ic:'book-open',label:'PRD · 페르소나',tx:541,ty:150,tw:352},
  {x:694,y:214,d:96,ic:'laptop',label:'UI · 코드 · 배포',tx:629,ty:324,tw:231},
  {x:605,y:377,d:96,ic:'square-check-big',label:'테스트 · UI 검수',tx:537,ty:486,tw:234},
  {x:259,y:377,d:96,ic:'file-search',label:'법령 · 판례 · 필지',tx:180,ty:486,tw:256},
  {x:170,y:214,d:96,ic:'mic',label:'통화 전사',tx:99,ty:324,tw:238}
 ];
 const nodes=work.map(a=>shape(s,a.x,a.y,a.d,a.d,C.pale,'none',0,'ellipse'));
 nodes.forEach(node=>s.shapes.connect(core,node,{kind:'straight',line:{fill:C.grid,width:2}}));
 await icon(s,'bot',456,263,48,C.paper);text(s,'Codex',418,330,124,47,31,{color:C.paper,bold:true,align:'center'});
 for(const [i,a]of work.entries()){
  await icon(s,a.ic,a.x+(a.d-47)/2,a.y+(a.d-47)/2,47,C.teal);
  text(s,a.label,a.tx,a.ty,a.tw,36,22,{bold:true,align:i===0?'left':'center'});
 }
}
{
 const s=slide({title:'CLJS 모듈러 모놀리스',chapter:'서비스 구조',sources:[ARCH,src('service/app/api/store.ts')],notes:'하나의 Sites 배포 안에 웹 화면과 CLJS 업무 모듈을 둔다. 업무 상태와 원본 저장의 세부 구현은 D1·R2다. 모듈은 공개 함수를 통해 연결한다.'});
 shape(s,52,154,852,331,'none',C.grid,2,'roundRect');text(s,'Sites',73,166,785,42,28,{bold:true});
 shape(s,120,261,133,133,C.pale,'none',0,'ellipse');await icon(s,'monitor',154,291,66,C.teal);
 text(s,'웹 화면',100,421,176,42,26,{bold:true,align:'center'});arrow(s,282,320,47,C.blue);
 shape(s,371,239,466,200,C.pale);text(s,'업무 모듈',393,254,424,38,25,{bold:true});
 const modules=['조직','재산','회계','회의','문서','법률'];
 for(const [i,v]of modules.entries())text(s,v,394+(i%3)*144,320+Math.floor(i/3)*64,126,39,25,{align:'center'});
}
{
 const s=slide({title:'AI 처리 흐름',sources:[src('docs/architecture/ai-runtime.md'),src('service/connectors/openai/workflows.mjs')],notes:'발표에서 System 1은 빠른 판단을 맡는 모델 단계를 뜻한다. 현재 모델 선택은 Luna Decisions가 담당하고, 후보 조건 필터와 모델의 추천 근거 설명을 매칭 흐름에 연결한다. 복잡한 분석은 선택된 고지능 모델로 처리한다. 코드의 생성 모델 허용 목록은 gpt-6-luna와 gpt-6.1-sol이며 음성은 whisper-1이다. 그림은 요청한 역할 구분이며 실제 API 성공을 검증했다는 주장은 아니다.'});
 const a=[['mic','Whisper','전사'],['git-branch','System 1','모델 선택 · 매칭 추천'],['bot','고지능 LLM','분석']];
 for(const [i,[ic,label,detail]]of a.entries()){
  const x=105+i*296;shape(s,x,184,152,152,i===1?C.teal:C.pale,'none',0,'ellipse');
  await icon(s,ic,x+42,222,69,i===1?C.paper:C.teal);
  text(s,label,x-32,359,216,47,29,{bold:true,align:'center'});
  text(s,detail,x-58,427,268,37,23,{color:C.blue,align:'center'});
  if(i<2)arrow(s,x+188,253,50,C.blue);
 }
}
{
 const s=slide({title:'하네스',sources:[PRD,src('AGENTS.md'),src('docs/architecture/testing.md'),src('qa/scenarios/hackathon-use-cases.md'),src('qa/ui-acceptance-2026-10-09.md')],notes:'PRD의 요구사항과 완료 조건을 시나리오, 순수 업무 규칙, 속성 기반 테스트와 화면 검수로 연결한다. 개발 에이전트 규칙은 AGENTS.md에 두고 PBT는 cljs.test·test.check를 사용한다. 실제 외부 API와 전체 E2E 완료 여부는 단위 검사와 별도로 다룬다.'});
 const a=[['book-open','PRD','요구사항 · 완료 조건'],['clipboard-list','유스케이스','정상 · 예외'],['square-check-big','PBT','속성 기반 테스트'],['monitor','UI 검수','화면 · 반응형']];
 for(const [i,[ic,label,detail]]of a.entries()){
  const x=54+i*229;shape(s,x+28,190,138,138,i===2?C.teal:C.pale,'none',0,'ellipse');
  await icon(s,ic,x+64,226,66,i===2?C.paper:C.teal);
  text(s,label,x,357,194,45,28,{bold:true,align:'center'});
  text(s,detail,x-5,426,205,35,20,{color:C.blue,align:'center'});
  if(i<3)arrow(s,x+187,254,31,C.blue);
 }
}
{
 const s=slide({bg:C.navy,number:false,notes:'업무 기록을 바탕으로 확인할 일을 안내하는 Events 흐름을 소개한다.'});text(s,'One More',65,115,836,124,88,{numeric:true,color:C.paper});text(s,'Thing',62,251,839,133,103,{numeric:true,color:C.paper});
}
{
 const s=slide({title:'ChatGPT 연동',chapter:'One More Thing',sources:[src('docs/architecture/mcp-and-skills.md'),src('service/connectors/mcp/tools/catalog.ts'),src('service/connectors/mcp/events/catalog.ts')],notes:'Skills는 종중 업무를 다루는 절차, MCP는 공개 업무 기능의 조회와 실행, MCP Events는 자료 변경 후 확인을 안내하는 연결을 담당한다. 모두 이번 구현 범위다. 도구 서버, 이벤트 모듈 코드와 실제 ChatGPT 수신·응답 검증은 구분한다. 서비스 Skill과 Events의 전달 상태는 배포 기록과 별도로 확인해야 한다.'});
 const a=[['book-open','Skills','업무 절차'],['network','MCP','조회 · 실행'],['bell-ring','MCP Events','변경 알림']];
 for(const [i,[ic,label,detail]]of a.entries()){
  const x=106+i*296;shape(s,x,182,152,152,i===1?C.teal:C.pale,'none',0,'ellipse');
  await icon(s,ic,x+40,222,72,i===1?C.paper:C.teal);
  text(s,label,x-48,364,248,48,30,{bold:true,align:'center'});
  text(s,detail,x-32,435,216,35,24,{align:'center',color:C.blue});
 }
}
{
 const s=slide({notes:'명문가 브랜드 화면으로 발표를 마친다. 소나무와 사당 배경은 특정 실제 장소가 아닌 개념 일러스트다.',number:false});
 await image(s,'clan-pine.png',0,0,960,540,'명문가 브랜드 개념 일러스트');
 text(s,'우리 가문은',58,119,377,43,27);text(s,'명문가',54,181,442,126,91,{font:SERIF,bold:true});text(s,'종중 운영의 모든것',59,332,424,43,27);
}

if(p.slides.items.length!==29)throw Error('Unexpected slide count');
for(const k of ['TAM','SAM','SOM']){const value=market[k].annual_subscription_revenue_won??market[k].annual_run_rate_won;if(market[k].clans*market.subscription_assumption.annual_fee_per_clan!==value)throw Error('Market arithmetic: '+k);}
await fs.writeFile(path.join(BUILD,'copy-v11.json'),JSON.stringify(copy,null,2));
await fs.writeFile(path.join(BUILD,'device-placements.json'),JSON.stringify(devicePlacements,null,2));
await fs.writeFile(path.join(BUILD,'authored-v11-proto.json'),JSON.stringify(p.toProto()));
const candidate=path.join(BUILD,`candidate-${REV}.pptx`);
await(await PresentationFile.exportPptx(p)).save(candidate);console.log('DRAFT_EXPORTED '+candidate);
const result=await finalizePresentation({workspaceDir:ROOT,candidatePath:candidate,finalPath:FINAL,pythonExecutable:path.join(BUNDLE,'python/python.exe'),integrityValidatorPath:path.join(SKILL,'container_tools/inspect_presentation_package_integrity.py'),layoutValidatorPath:path.join(SKILL,'container_tools/inspect_presentation_layout_geometry.py'),layoutArgs:['--expected-slide-size-emu','9144000,5143500','--validate-heading-fit','--validate-bullet-geometry'],requiredNativeChartOwnerSlides:chartOwners,materializeLiteralChartWorkbooks:true,fontPolicy:{basis:'design',families:[SANS,SERIF]},verifyArtifactToolImport:true,receiptPath:path.join(BUILD,`validation-${REV}.json`)});
console.log('FINALIZED '+JSON.stringify({path:result.finalPath,slides:result.packageIntegrity.slide_count,layoutFindings:result.presentationLayout.findingCount,sha256:result.finalSha256}));
const deck=await PresentationFile.importPptx(await FileBlob.load(FINAL));
for(const [i,s]of deck.slides.items.entries()){
 await fs.writeFile(path.join(PREVIEW,`slide-${String(i+1).padStart(2,'0')}.png`),new Uint8Array(await(await deck.export({slide:s,format:'png',scale:1.333333})).arrayBuffer()));
 console.log('RENDERED '+(i+1));
}
await fs.writeFile(path.join(BUILD,'revision-v11-receipt.json'),JSON.stringify({final:FINAL,sha256:result.finalSha256,slides:29,nativeCharts:chartOwners,deviceMockupSlides:devicePlacements.map(d=>d.slide),devicePlacements,sourceDeck:src('ppt/명문가_발표초안_v10_IR.pptx'),sourceDeckSha256:createHash('sha256').update(await fs.readFile(path.join(PPT,'명문가_발표초안_v10_IR.pptx'))).digest('hex'),template:'C:/Users/dldnj/OneDrive/문서/Premium Cloud Widescreen Multicolored.pptx',templateDeviceSlides:[306,312],evidenceAsOf:evidence.as_of},null,2));
console.log('DONE '+FINAL);
process.exit(0);
