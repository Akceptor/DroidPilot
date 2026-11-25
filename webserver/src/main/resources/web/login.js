'use strict';
const loginForm=document.getElementById('login-form');
const error=document.getElementById('login-error');
if(new URLSearchParams(location.search).has('expired')) {
  error.textContent='Your session ended. Sign in again to continue.';error.hidden=false;
}
loginForm.addEventListener('submit',async event=>{
  event.preventDefault();
  const button=document.getElementById('sign-in');button.disabled=true;button.textContent='Signing in…';error.hidden=true;
  try {
    const response=await fetch('/api/login',{method:'POST',credentials:'same-origin',cache:'no-store',headers:{'Content-Type':'application/json','X-DroidPilot-Request':'1'},body:JSON.stringify({username:loginForm.elements.username.value,password:loginForm.elements.password.value})});
    if(!response.ok) throw new Error(response.status===401?'Incorrect username or password.':response.status===429?'Too many attempts. Try again in a minute.':'Unable to sign in. Check that the phone service is available.');
    loginForm.elements.password.value='';location.replace('/admin');
  } catch(e) { error.textContent=e instanceof TypeError?'Cannot reach DroidPilot. Check your connection and try again.':e.message;error.hidden=false; }
  finally { button.disabled=false;button.textContent='Sign in'; }
});
