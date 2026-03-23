precision mediump float;

uniform vec3 u_LightPos;
uniform vec4 u_ambientColor;
uniform vec4 u_diffuseColor;
uniform vec4 u_specularColor;
const float specular_exp = 16.0;
varying vec3 v_Normal;
varying vec3 v_Position;

void main()
{
    vec3 normal = normalize(v_Normal);
    vec3 lightPosNorm = normalize(u_LightPos);
    vec3 cameraDir = normalize(-v_Position);

    // Two-sided lighting: abs() makes back faces light same as front
    float diffuse = abs(dot(lightPosNorm, normal));

    vec3 halfDir = normalize(lightPosNorm + cameraDir);
    // Only specular on front face to avoid "furry" back-face specular
    float specular = pow(max(dot(halfDir, normal), 0.0), specular_exp) * max(sign(dot(lightPosNorm, normal)), 0.0);

    gl_FragColor = u_ambientColor * (1.0 - diffuse)
                 + u_diffuseColor * (diffuse - specular)
                 + u_specularColor * specular;
}
