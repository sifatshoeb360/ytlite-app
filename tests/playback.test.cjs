// Runs the actual injected JavaScript without an Android SDK: node --test tests/*.test.cjs
const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const java = fs.readFileSync('app/src/main/java/com/example/ytlite/MainActivity.java','utf8');
function script(name) {
  const block = java.split(`String ${name} =`)[1].split(';\n')[0];
  return [...block.matchAll(/"(?:\\.|[^"\\])*"/g)].map(m=>JSON.parse(m[0])).join('');
}
function page({paused=false, end=100, time=10, ad=false}={}) {
  const timers=[], listeners={};
  const video={tagName:'VIDEO',paused,ended:false,seeking:false,readyState:4,
    currentTime:time,duration:end,seekable:{length:1,start:()=>0,end:()=>end},
    pause(){this.paused=true},
    play(){this.calls=(this.calls||0)+1;this.paused=false;return Promise.resolve()}};
  const player={classList:{contains:()=>ad},playVideo(){},getVideoData:()=>({video_id:'test'})};
  const document={addEventListener:(n,f)=>listeners[n]=f,
    querySelector:s=>s==='video'?video:s==='.html5-video-player'?player:null,
    getElementById:()=>true};
  const context={document,Document:function(){},HTMLMediaElement:function(){},
    location:{pathname:'/watch',search:'?v=test'},setTimeout:f=>timers.push(f),setInterval:()=>{},
    addEventListener:()=>{}};
  context.window=context; context.HTMLMediaElement.prototype.pause=function(){};
  vm.createContext(context);vm.runInContext(script('PAGE_JS'),context);
  return {context,video,player,timers,listeners,run:n=>vm.runInContext(script(n),context)};
}
test('playing video is primed without dragging progress bar',()=>{
  const p=page();p.timers.shift()();assert.equal(p.video.currentTime,10.25);
});
test('notification Play seeks a paused pipeline and falls back from no-op API',()=>{
  const p=page({paused:true});p.run('PLAY_JS');
  assert.equal(p.video.currentTime,10.25);assert.equal(p.video.calls,1);
});
test('throwing player API still falls back to media element',()=>{
  const p=page({paused:true});p.player.playVideo=()=>{throw Error('unavailable')};
  p.run('PLAY_JS');assert.equal(p.video.calls,1);
});
test('nudge stays in seekable range near end and skips ads/unseekable streams',()=>{
  const p=page({time:99.9});assert.equal(p.context.__ytlNudge(),true);
  assert.equal(p.video.currentTime,99.65);
  p.video.seekable.length=0;assert.equal(p.context.__ytlNudge(),false);
  assert.equal(page({ad:true}).context.__ytlNudge(),false);
});
test('queued pause recovery respects a later explicit Pause',()=>{
  const p=page({paused:true});p.context.__ytlBg=Date.now();
  p.listeners.pause({target:p.video});p.run('PAUSE_JS');
  p.timers.forEach(f=>f());assert.equal(p.video.calls,undefined);
});
test('ordinary nudge does not resume paused video',()=>{
  const p=page({paused:true});assert.equal(p.context.__ytlNudge(),false);
  assert.equal(p.video.currentTime,10);
});
test('priming uses the YouTube seek API when available',()=>{
  const p=page();let target;
  p.player.seekTo=(value)=>{target=value};
  p.context.__ytlNudge();assert.equal(target,10.25);
});
test('priming falls back if the YouTube seek API throws',()=>{
  const p=page();p.player.seekTo=()=>{throw Error('unavailable')};
  p.context.__ytlNudge();assert.equal(p.video.currentTime,10.25);
});
