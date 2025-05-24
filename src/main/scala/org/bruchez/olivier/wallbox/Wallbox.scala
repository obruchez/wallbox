package org.bruchez.olivier.wallbox

case class Wallbox(username: String, password: String, chargerId: String) {

  // See https://github.com/SKB-CGN/wallbox

  /*
let password = 'YourPassword';
let email = 'you@email.com';
let charger_id = 'idOfTheCharger';
let wallbox_token = '';
let conn_timeout = 3000;

const BASEURL = 'https://api.wall-box.com/';
const URL_AUTHENTICATION = 'auth/token/user';
const URL_CHARGER = 'v2/charger/';
const URL_CHARGER_CONTROL = 'v3/chargers/';
const URL_CHARGER_MODES = 'v4/chargers/';
const URL_CHARGER_ACTION = '/remote-action';
const URL_STATUS = 'chargers/status/';
const URL_CONFIG = 'chargers/config/';
const URL_REMOTE_ACTION = '/remote-action/';
const URL_ECO_SMART = '/eco-smart/';
   */

  /*
  Get token:

  const options = {
    url: BASEURL + URL_AUTHENTICATION,
    timeout: conn_timeout,
    method: 'POST',
    headers: {
        'Authorization': 'Basic ' + Buffer.from(email + ":" + password).toString('base64'),
        'Accept': 'application/json, text/plain, *//*',
        'Content-Type': 'application/json;charset=utf-8',
    }
  };
  */

  /*
  Control the Wallbox:

  const options = {
    url: BASEURL + URL_CHARGER + charger_id,
    timeout: conn_timeout,
    method: 'PUT',
    headers: {
        'Authorization': 'Bearer ' + wallbox_token,
        'Accept': 'application/json, text/plain, *//*',
        'Content-Type': 'application/json;charset=utf-8',
    },
    data: JSON.stringify({
      [key]: value
    })
  }
  */

  // etc.
}
