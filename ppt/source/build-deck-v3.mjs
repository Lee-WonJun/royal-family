import fs from 'node:fs/promises';
import path from 'node:path';
import {createRequire} from 'node:module';
import {fileURLToPath,pathToFileURL} from 'node:url';

const ROOT=process.env.ROYAL_FAMILY_WORKSPACE || path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const BUNDLE=process.env.CODEX_ARTIFACT_RUNTIME || 'C:/Users/dldnj/.cache/codex-runtimes/codex-primary-runtime/dependencies';
const MODULES=path.join(BUNDLE,'node/node_modules');
process.env.RUNTIME_NODE_MODULES=MODULES;
const rr=createRequire(path.join(MODULES,'__royal__.cjs'));
const {Presentation,PresentationFile,FileBlob}=await import(pathToFileURL(rr.resolve('@oai/artifact-tool')).href);
const sharp=rr('sharp');
const {GlobalFonts}=rr('@napi-rs/canvas');
GlobalFonts.registerFromPath('C:/Windows/Fonts/NotoSansKR-VF.ttf','Noto Sans KR');
GlobalFonts.registerFromPath('C:/Windows/Fonts/calibril.ttf','Calibri Light');
const SKILL='C:/Users/dldnj/.codex/plugins/cache/openai-primary-runtime/presentations/26.1007.11041/skills/presentations';
const {finalizePresentation,applyPresentationChartFont}=await import(pathToFileURL(path.join(SKILL,'container_tools/artifact_tool_utils.mjs')).href);
const PPT=path.join(ROOT,'ppt'), ASSETS=path.join(PPT,'assets');
const BUILD=path.join(ROOT,'.codex/ppt-build-20261009-template');
const REV=process.env.PPT_REVISION || 'v5_Cloud';
const PREVIEW=path.join(PPT,`preview-${REV.split('_')[0]}`);
const FINAL=path.join(PPT,`명문가_발표초안_${REV}.pptx`);
await Promise.all([fs.mkdir(BUILD,{recursive:true}),fs.mkdir(PREVIEW,{recursive:true}),fs.mkdir(path.join(BUILD,'icons-v3'),{recursive:true})]);
const reference=JSON.parse(await fs.readFile(path.join(ASSETS,'cloud-template-v3.json'),'utf8'));
const market=JSON.parse(await fs.readFile(path.join(PPT,'data/market-estimate-v3.json'),'utf8'));
const C={...reference.palette,white:'#FFFFFF',dark:'#1B1B1B',light:'#D8D8D8'};
const SANS='Noto Sans KR', NUM='Calibri Light';
const p=Presentation.create({slideSize:{width:960,height:540}});
const artwork=[],copy=[],chartOwners=[],tableOwners=[];
let artId=10000;
const src=f=>path.join(ROOT,f).replaceAll('\\','/');
const PLAN=src('rawdata/PPTPlan/script.md'), PRD=src('docs/prd/hackathon-prd.md');
const NEWS='https://www.jmbc.co.kr/news/view/68182';
const REGISTER=market.registered_clans.source;
const PRICING=market.subscription_assumption.benchmark;
const LAW=market.precedent_search.source;
const fee=market.subscription_assumption.annual_fee_per_clan;
const displayEok=won=>(Math.round(won/1e6)/100).toFixed(2);
if(market.TAM.clans*fee!==market.TAM.annual_subscription_revenue_won)throw Error('TAM arithmetic mismatch');
if(market.SAM.clans*fee!==market.SAM.annual_subscription_revenue_won)throw Error('SAM arithmetic mismatch');
if(market.SOM.clans*fee!==market.SOM.annual_run_rate_won)throw Error('SOM arithmetic mismatch');

function text(s,value,x,y,w,h,size=28,o={}){
  const sh=s.shapes.add({geometry:'textbox',name:value.replaceAll('\n',' '),position:{left:x,top:y,width:w,height:h},fill:'none',line:{fill:'none',width:0}});
  sh.text=value;
  sh.text.style={typeface:o.numeric?NUM:SANS,fontSize:size,bold:o.bold??false,color:o.color||C.text,alignment:o.align||'left',verticalAlignment:'top',wrap:'none',autoFit:'none',insets:0};
  if(o.link)sh.text.get(value).link={uri:o.link,isExternal:true};
  copy.push({slide:p.slides.items.length,text:value,size});
  return sh;
}
function cloneArt(s,original,x,y,w,h,{fill,line,alpha}={}){
  const el=structuredClone(original);
  el.id=String(artId++); el.name='Template artwork '+el.id;
  el.creationId='';el.children=[];el.paragraphs=[];el.textStyle={};el.effects=[];
  for(const k of ['connector','fontReference','lineReference','fillReference','effectReference'])delete el[k];
  el.bbox={xEmu:Math.round(x*9525),yEmu:Math.round(y*9525),widthEmu:Math.round(w*9525),heightEmu:Math.round(h*9525)};
  if(fill)el.shape.fill={type:1,color:{type:1,value:fill.replace('#',''),...(alpha?{transform:{alpha}}:{})},gradientStops:[],pictureEffects:[]};
  if(line)el.shape.line={...(el.shape.line||{}),fill:{type:1,color:{type:1,value:line.replace('#','')},gradientStops:[],pictureEffects:[]},widthEmu:19050,style:1};
  artwork.push({slideIndex:p.slides.items.indexOf(s),element:el});
}
function circle(s,x,y,size,color,{outline=false}={}){
  cloneArt(s,reference.circle,x,y,size,size,{fill:outline?C.white:color,line:color});
}
function slide({title,bg=C.white,notes='',sources=[PLAN],status,color=C.blue}={}){
  const s=p.slides.add();s.background.fill=bg;
  cloneArt(s,reference.footer,0,506.4,960,33.6);
  cloneArt(s,reference.footer,58,506.4,38,33.6,{fill:color,alpha:100000});
  text(s,String(p.slides.items.length),59,513,36,23,16,{numeric:true,align:'center',color:C.white});
  text(s,'명문가',116,514,430,21,12,{color:bg===C.dark?'#D8D8D8':C.text});
  if(title)text(s,title,46,25,868,64,38,{align:'center'});
  if(status)text(s,status,50,478,860,24,16,{color:C.text});
  s.speakerNotes.text=[notes,...sources.map(u=>'출처: '+u),'디자인 참고: '+reference.referencePath].filter(Boolean).join('\n');
  return s;
}
async function icon(s,name,x,y,size,color=C.blue,{bare=false}={}){
  if(!bare)circle(s,x,y,size,color);
  const dest=path.join(BUILD,'icons-v3',`${name}-${bare?color.replace('#',''):'white'}.png`);
  try{await fs.access(dest);}catch{
    const svg=(await fs.readFile(path.join(ASSETS,'icons',name+'.svg'),'utf8')).replaceAll('stroke="currentColor"',`stroke="${bare?color:C.white}"`);
    await sharp(Buffer.from(svg),{density:768}).resize(768,768).png().toFile(dest);
  }
  const pad=bare?0:size*0.22;
  s.images.add({blob:await fs.readFile(dest),contentType:'image/png',alt:'Lucide '+name,fit:'contain',position:{left:x+pad,top:y+pad,width:size-pad*2,height:size-pad*2}});
}
async function image(s,file,x,y,w,h,alt,{data=false}={}){
  s.images.add({blob:await fs.readFile(path.join(data?path.join(PPT,'data'):ASSETS,file)),contentType:file.endsWith('.jpg')?'image/jpeg':'image/png',alt,fit:'contain',position:{left:x,top:y,width:w,height:h}});
}
async function arrow(s,x,y,size=34){await icon(s,'arrow-right',x,y,size,C.text,{bare:true});}
async function pair(title,items,status='구현 목표'){
  const s=slide({title,notes:items.map(i=>i.uc+': '+i.note).join('\n'),sources:[PLAN,PRD],status});
  for(const [i,t] of items.entries()){
    const x=i?588:132;
    await icon(s,t.icon,x,151,180,i?C.green:C.blue);
    text(s,t.label,i?501:46,357,412,53,30,{align:'center'});
    text(s,t.detail,i?501:46,414,412,40,22,{color:C.text,align:'center'});
  }
  return s;
}

// 01. Opening recording cue.
{
 const s=slide({title:'아버지와의 통화',notes:'발표자는 통화 녹음을 외부 플레이어로 재생한다.',sources:[PLAN,src('rawdata/TTS/02_전사_보완본.md')]});
 await icon(s,'phone',350,148,260,C.blue);
 text(s,'통화 녹음',46,441,868,35,23,{align:'center'});
}
// 02. Opening question in the template's divider color.
{
 const s=slide({bg:C.blue,notes:'청중에게 자신의 종중을 떠올릴 시간을 준다.'});
 text(s,'여러분의 종중은\n안녕하십니까?',78,169,804,165,54,{align:'center',color:C.white});
}
// 03. Personal background.
{
 const s=slide({notes:'전주이씨임영대군파종중 18대손이라는 발표자의 경험.'});
 text(s,'18',72,100,350,259,210,{numeric:true,color:C.blue});
 text(s,'대손',274,297,150,68,41,{color:C.blue});
 text(s,'전주이씨\n임영대군파종중',474,155,420,119,34);
 await icon(s,'git-branch',660,318,94,C.blue);
}
// 04. Scope is not restricted to a land-use category.
{
 const s=slide({title:'종중',notes:'뿌리와 종원, 공동재산, 기록을 소개한다. 임야, 농지, 대지와 건물 등 재산 종류로 서비스 범위를 제한하지 않는다.',sources:[PLAN,PRD]});
 const a=[['trees','뿌리',C.blue],['landmark','공동재산',C.gold],['files','기록',C.green],['users-round','종원',C.red]];
 for(const [i,v] of a.entries()){const x=57+i*232;await icon(s,v[0],x,166,150,v[2]);text(s,v[1],x-28,357,206,50,27,{align:'center'});}
}
// 05. Main broadcast report.
{
 const s=slide({title:'종중 재산 분쟁',notes:'전주MBC 2026-09-22 보도. 종중 대표를 사칭한 사람과의 토지 거래 및 소송 사례. 발표자 가문의 경험과는 다른 사례다.',sources:[NEWS]});
 text(s,'가짜 대표\n땅거래\n소송',50,163,263,215,36,{color:C.red});
 await image(s,'news-jmbc.jpg',329,141,581,327,'전주MBC 보도 화면 원문 캡처');
 text(s,'전주MBC 2026.09.22',329,479,582,23,16,{link:NEWS});
}
// 06. Sources provided by the user.
{
 const urls=['https://www.bubb.co.kr/news/articleView.html?idxno=4550','https://www.cctoday.co.kr/news/articleView.html?idxno=2233033','https://magazine.hankyung.com/business/article/202607168245b'];
 const s=slide({title:'종손 지위와 토지 계약',notes:'종손 지위 사건, 임대 계약에 대한 양측의 주장, 대표권 없는 매매와 대금 반환 쟁점을 소개한다. 개별 사건에 대한 법적 판단을 일반화하지 않는다.',sources:urls});
 const a=[['users-round','종손 지위','news-bubb-title.jpg','법치뉴스 2026.04.28',C.blue],['landmark','임대 계약','news-cctoday-title.jpg','충청투데이 2026.07.10',C.green],['briefcase-business','대표권','news-hankyung-title.jpg','한경BUSINESS 2026.07.24',C.red]];
 for(const [i,v] of a.entries()){const y=127+i*119;await icon(s,v[0],66,y+8,55,v[4]);text(s,v[1],47,y+70,188,34,21);await image(s,v[2],251,y,659,i===2?98:79,v[3]+' 기사 제목 캡처');text(s,v[3],252,y+(i===2?104:83),658,22,15,{link:urls[i]});}
}
// 07. Measured public-search evidence, clearly separated from dispute incidence.
{
 const s=slide({title:'종중 관련 공개 판례 검색',notes:'국가법령정보센터, 검색어 종중, 전체 검색, 기간 제한 없음. 2026-10-09 화면에 1416건이 표시됐다. 검색 항목에는 같은 사건의 심급과 요약 등 중복, 조세 사건이 포함될 수 있다. 전국 연간 분쟁 건수나 고유 사건 수를 측정한 통계가 아니다.',sources:[LAW],status:'누적 검색 결과    연간 분쟁 건수와 다름    2026.10.09 조회'});
 text(s,'1,416',51,104,636,190,151,{numeric:true,color:C.red});
 text(s,'건',435,218,112,63,39,{color:C.red});
 await icon(s,'scale',796,148,105,C.red);
 await image(s,'evidence/precedent-search-20261009.jpg',57,317,849,142,'법제처 종중 전체검색 1416건 표시 화면',{data:true});
}
// 08. Case-specific amounts and duration, not national totals.
{
 const s=slide({title:'보도 사례의 시간과 금액',notes:'전주MBC는 4년 가까운 법정 다툼을 보도했다. 한경BUSINESS는 41억8500만원의 매매계약 사례를 소개했다. 두 수치는 서로 다른 사건이며, 41.85억원은 매매대금으로 피해액이나 분쟁비용이 아니다.',sources:[NEWS,'https://magazine.hankyung.com/business/article/202607168245b']});
 text(s,'약 4년',58,149,376,155,97,{numeric:true,color:C.blue,align:'center'});
 text(s,'법정 다툼',58,328,376,46,28,{align:'center'});
 text(s,'전주MBC 보도 사례',58,404,376,31,18,{align:'center'});
 text(s,'41.85',483,145,420,155,107,{numeric:true,color:C.red,align:'center'});
 text(s,'억원의 매매대금',483,328,420,47,27,{align:'center'});
 text(s,'한경BUSINESS 보도 사례',483,404,420,31,18,{align:'center'});
}
// 09. The interview's problem structure.
{
 const s=slide({title:'재산과 기록을 이어받는 일',notes:'과거의 유산과 현재의 일상, 다음 세대 인수인계를 설명한다. 발표 대본의 수정 내용을 반영했다.',sources:[PLAN,src('rawdata/TTS/02_전사_보완본.md')]});
 const a=[['landmark','과거의 유산','공동재산',C.blue],['clipboard-list','현재의 일상','회의, 동의, 기록',C.gold],['user-round-cog','다음 세대의 관리','인수인계',C.red]];
 for(const [i,v] of a.entries()){const x=77+312*i;await icon(s,v[0],x,149,182,v[3]);text(s,v[1],x-31,355,243,45,25,{align:'center'});text(s,v[2],x-31,414,243,34,20,{align:'center'});}
}
// 10. Personal experience.
{
 const s=slide({bg:C.dark,notes:'발표자가 이전 종중 서비스 창업에 실패했던 경험을 이야기한다.',color:C.red});
 text(s,'한 번\n실패했습니다',76,157,808,181,59,{color:C.white});
}
// 11. Concrete AI tasks.
{
 const s=slide({title:'AI와 다시 시작',notes:'전사, 문서 초안, 자료 비교는 이번 서비스의 구현 목표다.',sources:[PLAN,PRD],status:'구현 목표'});
 const a=[['mic','전사',C.blue],['file-pen-line','문서 초안',C.green],['file-search','자료 비교',C.red]];
 for(const [i,v] of a.entries()){const x=76+313*i;await icon(s,v[0],x,150,184,v[2]);text(s,v[1],x-36,380,256,50,29,{align:'center'});}
}
// 12. Brand reveal.
{
 const s=slide({notes:'제품명 명문가를 소개한다.',sources:[PLAN,PRD]});
 text(s,'우리 가문은',62,156,474,48,31);
 text(s,'명문가',58,213,507,143,88,{color:C.blue});
 text(s,'종중 운영과 자료 검토',63,390,482,43,25);
 await icon(s,'users-round',627,169,252,C.blue);
}
// 13. Architecture.
{
 const s=slide({title:'모듈러 모놀리스',notes:'설계안. CLJS와 Vinext에서 모듈 공개 기능을 호출한다. 순수 업무 규칙과 저장소, AI, 발송 경계를 분리한다. 외부 업무는 mock으로 교체한다. PBT는 검증 계획이다.',sources:[PLAN,src('docs/architecture/system-design.md'),src('docs/architecture/testing.md')],status:'설계안    외부 업무는 mock으로 교체'});
 text(s,'CLJS + Vinext',50,101,430,37,23);
 text(s,'PBT   cljs.test + test.check',476,105,434,32,19,{align:'right'});
 await icon(s,'monitor',68,225,130,C.blue);await arrow(s,255,272,37);
 await icon(s,'blocks',404,193,157,C.gold);text(s,'모듈 공개 기능',329,373,306,37,24,{align:'center'});text(s,'명부  회의  문서\n재산  회계  검토',329,419,306,58,20,{align:'center'});
 await arrow(s,673,272,37);
 await icon(s,'database',799,174,84,C.green);text(s,'D1 + R2',748,267,184,35,22,{align:'center'});
 await icon(s,'bot',799,331,84,C.red);text(s,'OpenAI',748,425,184,35,22,{align:'center'});
}
// 14. Full scope.
{
 const s=slide({title:'설립부터 검토까지',notes:'종중 설립 준비에서 운영, 동의 수집, 법률 확인까지 이어지는 구현 목표.',sources:[PLAN,PRD],status:'구현 목표'});
 const a=[['folder-check','설립 준비',C.blue],['users','운영',C.gold],['square-check-big','동의',C.green],['scale','법률 확인',C.red]];
 for(const [i,v] of a.entries()){const x=53+232*i;await icon(s,v[0],x,177,146,v[2]);text(s,v[1],x-23,374,192,46,25,{align:'center'});if(i<3)await arrow(s,x+175,232,28);}
}
// 15. UC-01.
await pair('설립 준비',[
 {uc:'UC-01 서류',icon:'file-pen-line',label:'준비 서류',detail:'추가 확인 항목',note:'보유 서류와 추가 확인 항목을 정리해 준비 문서 초안을 만든다.'},
 {uc:'UC-01 추천',icon:'briefcase-business',label:'법무사 후보',detail:'조건별 추천',note:'가상 후보와 추천 근거를 제시한다. 실제 제휴나 접수를 뜻하지 않는다.'},
],'구현 목표    법무사 후보는 가상 데이터');
// 16. UC-02, UC-03.
await pair('회의 녹음과 기존 회의록',[
 {uc:'UC-02',icon:'mic',label:'녹음 전사',detail:'인명, 금액 확인',note:'원문에 연결한 전사와 회의록 초안.'},
 {uc:'UC-03',icon:'file-search',label:'회의록 검색',detail:'원문과 결정 근거',note:'당시 규약, 참석 기록과 문서 버전을 조회.'},
]);
// 17. UC-04, UC-05.
await pair('문서 초안과 동의 요청',[
 {uc:'UC-04',icon:'file-pen-line',label:'문서 초안',detail:'검토 후 사용',note:'근거 자료를 이용해 문서 초안을 만들고 담당자가 검토한다.'},
 {uc:'UC-05',icon:'bell-ring',label:'동의 요청',detail:'문서 버전별 응답',note:'찬성, 거절, 미응답을 구분해 기록한다. 동의 기록을 법적 결의 효력으로 확정하지 않는다.'},
],'구현 목표    외부 푸시는 mock');
// 18. UC-06, UC-07.
await pair('법률 확인과 재산 자료 변화',[
 {uc:'UC-06',icon:'book-open',label:'법령과 규약',detail:'근거 확인',note:'공식 원문과 등록한 규약의 출처를 분리해 조회.'},
 {uc:'UC-07',icon:'map',label:'재산 자료 변화',detail:'이전 자료와 비교',note:'소유자 표시 등의 변화와 근거를 비교한다. 시연에는 모의 후속 자료를 사용한다.'},
],'구현 목표    자료 변화 시연은 모의 데이터');
// 19. UC-08.
await pair('문제 점검과 변호사 후보',[
 {uc:'UC-08 점검',icon:'clipboard-list',label:'누락, 불일치',detail:'근거와 다음 작업',note:'자료에 근거한 누락과 불일치. 위법 여부를 자동 확정하지 않는다.'},
 {uc:'UC-08 추천',icon:'briefcase-business',label:'변호사 후보',detail:'상담자료 준비',note:'조건에 맞는 가상 후보와 전달자료를 준비한다. 실제 수임 완료를 뜻하지 않는다.'},
],'구현 목표    변호사 후보는 가상 데이터');
// 20. All registered clans as the observable market baseline.
{
 const s=slide({title:'부동산 등기용 등록 종중',notes:market.registered_clans.source_detail+'\n'+market.registered_clans.boundary+'\n서비스는 임야, 농지, 대지, 건물 등 재산 종류와 무관하게 종중의 설립 준비와 운영 기록을 다룬다.',sources:[REGISTER,PRD],status:'2024.6 기준    대한법무사협회 등기법포럼 자료'});
 text(s,'237,071',43,111,676,177,133,{numeric:true,color:C.blue});
 text(s,'개 종중',523,226,184,54,28);
 const a=[['trees','임야',C.blue],['map','농지',C.gold],['map-pin','대지',C.green],['landmark','건물',C.red]];
 for(const [i,v] of a.entries()){const x=110+i*215;await icon(s,v[0],x,317,94,v[2]);text(s,v[1],x-35,432,164,35,21,{align:'center'});}
}
// 21. A benchmark-informed assumption, not an announced product price.
{
 const s=slide({title:'시장 환산에 쓴 구독료',notes:market.subscription_assumption.benchmark_detail+'\n'+market.subscription_assumption.boundary,sources:[PRICING],status:'계산 가정    VAT와 전문가 수임료 제외'});
 await icon(s,'notebook-text',81,181,147,C.gold);
 text(s,'종중당 연',297,142,591,51,28);
 text(s,'50',292,197,351,148,123,{numeric:true,color:C.gold});
 text(s,'만원',512,279,245,66,41,{color:C.gold});
 text(s,'비교 요금  ZUZU Fund Service 연 50만원',297,396,607,59,21);
}
// 22. Revenue-scale estimates with disclosed scope and assumptions.
{
 const s=slide({title:'TAM  SAM  SOM',notes:market.market_boundary+'\n'+(market.SAM.definition||'')+'\n'+market.rounding+'\nTAM과 SAM 모수, 구독료, SOM 확보율은 첨부 계산 JSON에 보존한다.',sources:[REGISTER,PRICING,src('ppt/data/market-estimate-v3.json')],status:'연간 구독매출 환산    연 50만원 가정    SOM은 3년 도달 시나리오'});
 const a=[['TAM',market.TAM.clans,market.TAM.annual_subscription_revenue_won,C.blue,'등록 종중'],['SAM',market.SAM.clans,market.SAM.annual_subscription_revenue_won,C.green,market.SAM.short_label||'서비스 대상'],['SOM',market.SOM.clans,market.SOM.annual_run_rate_won,C.red,'확보율 가정 '+(market.SOM.penetration_assumption*100)+'%']];
 for(const [i,v] of a.entries()){
   const x=41+i*312;circle(s,x,160,246,v[3],{outline:true});
   text(s,v[0],x+10,121,226,43,31,{numeric:true,color:v[3],align:'center'});
   text(s,i<2?Math.round(v[2]/1e8).toLocaleString('en-US'):displayEok(v[2]),x+4,213,238,85,i<2?62:68,{numeric:true,color:v[3],align:'center'});
   text(s,'억원 / 년',x+13,307,220,35,23,{align:'center'});
   text(s,v[1].toLocaleString('en-US')+'개',x+13,350,220,31,22,{align:'center'});
   text(s,v[4],x-17,428,280,36,19,{align:'center'});
 }
}
// 23. Scenario chart: adoption is a modeling input, not measured demand.
{
 const s=slide({title:'SOM 확보율 시나리오',notes:'3년 도달 시점의 연환산 매출. 확보율은 조사된 구매율이 아닌 사업 시나리오다. 고객 수는 SAM에 가정 확보율을 곱해 정수 반올림했다.\n'+JSON.stringify(market.SOM_scenarios),sources:[src('ppt/data/market-estimate-v3.json'),REGISTER,PRICING],status:'3년 도달 가정    연 50만원    실제 확보 고객이나 현재 매출 아님'});
 const q=market.SOM_scenarios;
 const ch=s.charts.add('bar',{
   position:{left:103,top:135,width:735,height:313},
   categories:q.map(v=>`${(v.penetration_assumption*100)}%  ${v.clans.toLocaleString('en-US')}개`),
   series:[{name:'연환산 매출',values:q.map(v=>v.display_eok),points:[{idx:0,fill:C.blue},{idx:1,fill:C.green},{idx:2,fill:C.red}]}],
   barOptions:{direction:'bar',grouping:'clustered',gapWidth:68,varyColors:true},hasLegend:false,
   xAxis:{visible:true,majorGridlines:null,textStyle:{fontSize:20,fill:C.text},line:{fill:'none',width:0}},
   yAxis:{visible:true,min:0,max:30,majorUnit:10,numberFormatCode:'0',title:'억원 / 년',majorGridlines:{fill:'#E6E6E6',width:1},textStyle:{fontSize:17,fill:C.text}},
   dataLabels:{showValue:true,position:'outEnd',numberFormatCode:'0.00',textStyle:{fontSize:25,fill:C.text}},
   chartFill:C.white,plotAreaFill:C.white,
 });
 applyPresentationChartFont(ch,{fontFamily:SANS});chartOwners.push(p.slides.items.length);
}
// 24. Codex-assisted planning.
{
 const s=slide({title:'Codex로 기획 정리',notes:'통화 전사와 제출서로 PRD와 업무 흐름을 정리했다. ZUZU는 UI와 워크플로우 참고 대상이다. 통화 기반 가상 종원 페르소나를 구성했다. 서비스 UI 구현 완료를 뜻하지 않는다.',sources:[PLAN,PRD,src('qa/scenarios/hackathon-use-cases.md')]});
 await icon(s,'mic',68,216,149,C.blue);text(s,'통화, 제출서',49,397,188,42,24,{align:'center'});await arrow(s,266,272,34);
 await icon(s,'laptop',388,216,149,C.gold);text(s,'Codex',369,397,188,42,27,{numeric:true,align:'center'});await arrow(s,588,272,34);
 const a=[['clipboard-list','PRD'],['network','UI 흐름 구상'],['users-round','가상 종원']];
 for(const [i,v] of a.entries()){await icon(s,v[0],706,151+i*107,65,[C.blue,C.green,C.red][i]);text(s,v[1],796,168+i*107,149,39,21);}
}
// 25. Public-data baseline and provided credit request.
{
 const s=slide({title:'공개 자료와 개발 준비',notes:'K-GeoP 공개 조회 기준값: 공주시 태봉동 산41-1, 임야 14154.0㎡, 조회일 2026-10-09. 소유권 확정 자료가 아니다. 오른쪽은 제공된 크레딧 적용 요청 화면으로 적용 완료와 잔액을 증명하지 않는다.',sources:[PLAN,PRD,'https://www.kgeop.go.kr/info/infoMap.do?initMode=L']});
 await icon(s,'map-pin',149,156,136,C.blue);text(s,'공주 태봉동 산41-1',48,331,411,51,27,{align:'center'});text(s,'14,154㎡',48,392,411,79,58,{numeric:true,color:C.blue,align:'center'});
 text(s,'크레딧 적용 요청',541,115,373,47,25,{align:'center'});await image(s,'credit-request.png',556,170,349,297,'제공된 API 크레딧 적용 요청 화면');
}
// 26. Divider.
{
 const s=slide({bg:C.dark,notes:'skills와 MCP Events 연동 목표를 소개한다.'});
 text(s,'One More',69,178,822,99,67,{numeric:true,color:C.white});text(s,'Thing',69,275,822,101,72,{numeric:true,color:C.blue});
}
// 27. Intended integration.
{
 const s=slide({title:'skills + MCP Events',notes:'자료 변화 이벤트를 보내고 ChatGPT가 도구로 근거를 조회하는 연동 목표. 실제 수신과 응답은 구현 후 검증한다.',sources:[PLAN,src('docs/architecture/mcp-and-skills.md')],status:'연동 목표'});
 const a=[['map','자료 변화',C.blue],['bell-ring','이벤트 전달',C.green],['messages-square','ChatGPT 확인',C.red]];
 for(const [i,v] of a.entries()){const x=75+i*316;await icon(s,v[0],x,156,168,v[2]);text(s,v[1],x-31,366,230,46,25,{align:'center'});if(i<2)await arrow(s,x+224,222,35);}
 text(s,'skills    자료 조회와 문제 확인 절차',53,437,855,36,22);
}
// 28. Closing.
{
 const s=slide({bg:C.blue,notes:'질문을 받는다.'});
 text(s,'감사합니다',63,174,833,101,64,{color:C.white,align:'center'});text(s,'Q&A',66,325,828,64,34,{numeric:true,color:C.white,align:'center'});
}
// 29. Editable calculation appendix.
{
 const s=slide({title:'추정 기준과 계산',notes:market.market_boundary+'\n'+market.registered_clans.source_detail+'\n'+market.subscription_assumption.benchmark_detail+'\n'+market.precedent_search.boundary,sources:[REGISTER,PRICING,LAW,src('ppt/data/market-estimate-v3.json')]});
 const rows=[['항목','기준과 계산','결과'],['TAM',`${market.TAM.clans.toLocaleString('en-US')}개 × 연 50만원`,displayEok(market.TAM.annual_subscription_revenue_won)+'억원 / 년'],['SAM',`${market.SAM.clans.toLocaleString('en-US')}개 × 연 50만원`,displayEok(market.SAM.annual_subscription_revenue_won)+'억원 / 년'],['SOM',`${market.SOM.clans.toLocaleString('en-US')}개 × 연 50만원`,displayEok(market.SOM.annual_run_rate_won)+'억원 / 년'],['확보율',`SAM의 ${market.SOM.penetration_assumption*100}%`,`${market.SOM.clans.toLocaleString('en-US')}개`],['분쟁 자료','공개 판례 전체검색, 기간 제한 없음','검색 결과 1,416건']];
 const tb=s.tables.add({rows:rows.length,columns:3,left:52,top:126,width:856,height:322,columnWidths:[116,454,286],values:rows});
 for(let r=0;r<rows.length;r++)for(let c=0;c<3;c++){const cell=tb.getCell(r,c);cell.fill=r===0?C.blue:C.white;cell.text.style={typeface:SANS,fontSize:r===0?20:19,bold:false,color:r===0?C.white:C.text,verticalAlignment:'middle',insets:12};}
 tb.borders.assign({fill:'#D8D8D8',width:1,style:'solid'});tableOwners.push(p.slides.items.length);
 text(s,'구독료와 확보율은 가정    수치는 현재 매출이나 연간 분쟁 통계가 아님',52,471,856,31,16);
}

// Reuse the reference's original native circles and footer geometry.
// No template placeholder copy or sample chart values are brought into the result.
const proto=p.toProto();
for(let index=0;index<proto.slides.length;index++){
  const native=artwork.filter(a=>a.slideIndex===index).map(a=>a.element);
  proto.slides[index].elements.unshift(...native);
}
let finalDeck=Presentation.load(proto);
for(const s of finalDeck.slides.items)s.speakerNotes.text+='\n픽토그램: Lucide Icons, ISC License, https://lucide.dev/icons/';
const candidate=path.join(BUILD,`candidate-${REV}.pptx`);
await (await PresentationFile.exportPptx(finalDeck)).save(candidate);
console.log('EXPORTED_DRAFT '+candidate);
const result=await finalizePresentation({workspaceDir:ROOT,candidatePath:candidate,finalPath:FINAL,pythonExecutable:path.join(BUNDLE,'python/python.exe'),integrityValidatorPath:path.join(SKILL,'container_tools/inspect_presentation_package_integrity.py'),layoutValidatorPath:path.join(SKILL,'container_tools/inspect_presentation_layout_geometry.py'),layoutArgs:['--expected-slide-size-emu','9144000,5143500','--validate-heading-fit','--validate-bullet-geometry',...tableOwners.flatMap(n=>['--require-native-table-slide',String(n)])],requiredNativeTableOwnerSlides:tableOwners,requiredNativeChartOwnerSlides:chartOwners,materializeLiteralChartWorkbooks:true,fontPolicy:{basis:'design',families:[SANS,NUM]},verifyArtifactToolImport:true,receiptPath:path.join(BUILD,`validation-${REV}.json`)});
console.log('FINALIZED '+JSON.stringify({path:result.finalPath,slides:result.packageIntegrity.slide_count,geometryFindings:result.presentationLayout.findingCount,sha256:result.finalSha256}));
finalDeck=await PresentationFile.importPptx(await FileBlob.load(FINAL));
for(const [i,s] of finalDeck.slides.items.entries()){
  await fs.writeFile(path.join(PREVIEW,`slide-${String(i+1).padStart(2,'0')}.png`),new Uint8Array(await (await finalDeck.export({slide:s,format:'png',scale:1.333333})).arrayBuffer()));
  console.log(`RENDERED ${i+1}/${finalDeck.slides.items.length}`);
}
await fs.writeFile(path.join(BUILD,'slide-copy-v3.json'),JSON.stringify(copy,null,2));
await fs.writeFile(path.join(BUILD,'verification-v3.json'),JSON.stringify({finalFile:path.basename(FINAL),slideCount:finalDeck.slides.items.length,sha256:result.finalSha256,templateSha256:reference.referenceSha256,templateReferenceMode:'visual inspiration and original native circle/footer artwork',nativeChartSlides:chartOwners,nativeTableSlides:tableOwners},null,2));
console.log('DONE '+FINAL);
process.exit(0);
