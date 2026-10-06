/** Swagger does not persist authorization or automatically export the browser's banking token. */
if(window.SwaggerUIBundle)window.SwaggerUIBundle({url:'/api/v1/docs/openapi',dom_id:'#swagger-ui',deepLinking:true,persistAuthorization:false,validatorUrl:null,displayRequestDuration:true});
else document.querySelector('#swagger-ui').textContent='Swagger UI dependencies are missing. In moneybags-frontend run npm.cmd install --prefix components, then npm.cmd --prefix components run build. The OpenAPI document is available at /api/v1/docs/openapi.';
