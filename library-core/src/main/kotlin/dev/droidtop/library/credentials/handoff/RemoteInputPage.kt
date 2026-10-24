package dev.droidtop.library.credentials.handoff

/**
 * The page a phone's browser opens for "Type from another device" (docs/SPEC.md): static HTML and a little script,
 * served by the session itself, with nothing fetched from outside. It shows the pairing code large until the console
 * confirms it, then one text box with Send, Enter, Backspace and a live-typing switch, and a Clipboard panel (Send to
 * console, Get console clipboard, Copy; at most 64 KB of text). The code is not secret from
 * the page (it is shown there to be compared), and each request carries it back so the server can tell the paired
 * page from any other client.
 */
object RemoteInputPage {
    private const val TITLE = "Type from another device"

    private const val STYLE = "body{font:18px system-ui,sans-serif;margin:24px;max-width:28em}" +
        "#code{font-size:56px;font-weight:700;letter-spacing:.08em;margin:16px 0}" +
        "textarea{display:block;width:100%;box-sizing:border-box;font-size:18px;padding:12px;margin:12px 0;height:7em}" +
        "h2{font-size:22px;margin:28px 0 0}#cmsg{min-height:1.5em}" +
        "input[type=text],input[type=password]{display:block;width:100%;box-sizing:border-box;font-size:20px;padding:12px;margin:12px 0}" +
        "button{font-size:18px;padding:12px 20px;margin:0 8px 8px 0}label{display:block;margin:8px 0}#msg{min-height:1.5em}"

    /** The page for a device that has been shown [code] (six digits). */
    fun html(code: String): String = document(
        "<h1>$TITLE</h1><p>Check that your console shows this code, then confirm it there.</p>" +
            "<div id=\"code\">${RemoteInput.spaced(code)}</div><p id=\"msg\"></p>" +
            "<div id=\"pad\" hidden>" +
            "<input id=\"t\" type=\"text\" autocomplete=\"off\" autocapitalize=\"off\" autocorrect=\"off\" spellcheck=\"false\">" +
            "<button id=\"send\">Send</button><button id=\"enter\">Enter</button><button id=\"back\">Backspace</button>" +
            "<label><input id=\"live\" type=\"checkbox\"> Live typing</label>" +
            "<h2>Clipboard</h2>" +
            "<textarea id=\"c\" autocomplete=\"off\" autocapitalize=\"off\" autocorrect=\"off\" spellcheck=\"false\" " +
            "placeholder=\"Text for the console's clipboard, or the console's clipboard once you get it\"></textarea>" +
            "<button id=\"cput\">Send to console</button><button id=\"cget\">Get console clipboard</button>" +
            "<button id=\"ccopy\">Copy</button><p id=\"cmsg\"></p></div>" +
            "<script>" + SCRIPT.replace("%CODE%", code) + "</script>",
    )

    /** A short page with one sentence, for every refusal. */
    fun message(text: String): String = document("<h1>$TITLE</h1><p>${text.replace("&", "&amp;").replace("<", "&lt;")}</p>")

    private fun document(body: String): String =
        "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<meta name=\"referrer\" content=\"no-referrer\"><title>$TITLE</title><style>$STYLE</style></head><body>$body</body></html>"

    private val SCRIPT = """
var code='%CODE%',base=location.pathname,box=document.getElementById('t'),live=document.getElementById('live'),
msg=document.getElementById('msg'),pad=document.getElementById('pad'),prev='',chain=Promise.resolve(),timer=0;
function say(t){msg.textContent=t||'';}
function secret(on){var t=on?'password':'text';if(box.type!==t){box.type=t;}}
function ended(){clearInterval(timer);pad.hidden=true;say('This session has ended. If the console shows a new code, open its new address.');}
function post(path,p){
  var b='code='+code;for(var k in p){b+='&'+k+'='+encodeURIComponent(p[k]);}
  return fetch(base+'/'+path,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:b})
    .then(function(r){if(!r.ok){throw r.status;}return r.json();});
}
function send(p){
  chain=chain.then(function(){
    return post('in',p).then(function(j){secret(j.secret);say(j.ok?'':'Nothing was typed. '+j.notice);},ended);
  });
}
var cbox=document.getElementById('c'),cmsg=document.getElementById('cmsg'),LIMIT=65536;
function csay(t){cmsg.textContent=t||'';}
function cfail(s){if(s===413){csay('That is more than 64 KB of text, the limit for the shared clipboard.');}else{ended();}}
document.getElementById('cput').onclick=function(){
  if(!cbox.value){csay('There is no text to send.');return;}
  if(new TextEncoder().encode(cbox.value).length>LIMIT){csay('That is more than 64 KB of text, the limit for the shared clipboard.');return;}
  post('clipput',{text:cbox.value}).then(function(j){csay(j.ok?'Sent to the console.':j.notice);},cfail);
};
document.getElementById('cget').onclick=function(){
  post('clipget',{}).then(function(j){if(j.ok){cbox.value=j.text;csay('Got the console clipboard.');}else{csay(j.notice);}},cfail);
};
document.getElementById('ccopy').onclick=function(){
  if(!cbox.value){csay('There is nothing to copy.');return;}
  function fallback(){cbox.focus();cbox.select();var ok=false;try{ok=document.execCommand('copy');}catch(e){}csay(ok?'Copied.':'Select the text and copy it.');}
  if(navigator.clipboard&&window.isSecureContext){navigator.clipboard.writeText(cbox.value).then(function(){csay('Copied.');},fallback);}else{fallback();}
};
function poll(){
  fetch(base+'/state').then(function(r){if(!r.ok){throw r.status;}return r.json();}).then(function(j){
    if(j.state==='paired'){document.getElementById('code').hidden=true;pad.hidden=false;secret(j.secret);if(!msg.textContent){say('Connected');}}
  },ended);
}
function diff(a,b){var i=0;while(i<a.length&&i<b.length&&a[i]===b[i]){i++;}return{back:a.length-i,text:b.slice(i)};}
document.getElementById('send').onclick=function(){if(!live.checked&&box.value){send({text:box.value});}box.value='';prev='';box.focus();};
document.getElementById('enter').onclick=function(){send({enter:1});};
document.getElementById('back').onclick=function(){send({back:1});};
live.onchange=function(){prev=box.value;};
box.oninput=function(){if(!live.checked){return;}var d=diff(prev,box.value);prev=box.value;if(d.back||d.text){send(d);}};
poll();timer=setInterval(poll,1500);
""".trimIndent()
}
