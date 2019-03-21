
var city = "Romainville";
var typeformId = "Kem4Tn";
	
var center;
var codePostaux = [];
var codeInsee;
var contour;

var requestCityGeoData = new XMLHttpRequest();
requestCityGeoData.open('GET', 'https://geo.api.gouv.fr/communes?fields=code,nom,codesPostaux,centre,contour&nom='+city, true);

requestCityGeoData.onload = function() {
    if (requestCityGeoData.status >= 200 && requestCityGeoData.status < 400) {
    // Success!
        var data = JSON.parse(requestCityGeoData.responseText);
        codePostaux = data[0]["codesPostaux"];
        codeInsee = data[0]["code"];
        center = data[0]["centre"]["coordinates"];
        contour = data[0]["contour"];
        try { onCityGeoDataLoaded(); } catch(e){}
    } else {
    // We reached our target server, but it returned an error

    }
};
requestCityGeoData.send();