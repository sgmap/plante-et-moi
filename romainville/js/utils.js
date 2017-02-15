
/*
  URL UTILS
*/

function getParameterByName(name) {
    var url = window.location.href;
   	name = name.replace(/[\[\]]/g, "\\$&");
   	var regex = new RegExp("[?&]" + name + "(=([^&#]*)|&|#|$)"),
    results = regex.exec(url);
   	if (!results) return null;
   	if (!results[2]) return '';
  	return decodeURIComponent(results[2].replace(/\+/g, " "));
}

/*
  WORDS UTILS
*/

function isVowelOrH(c) {
    return ['a', 'e', 'i', 'o', 'u', 'h'].indexOf(c.toLowerCase()) !== -1;
}

/*
  DOM UTILS
*/

function show(itemID) {
	var itemDom = document.getElementById(itemID);
	itemDom.classList.remove(itemID+'--invisible');
	itemDom.scrollIntoView();
}

/*
  Array UTILS
*/

Array.prototype.random = function () {
  return this[Math.floor((Math.random()*this.length))];
}