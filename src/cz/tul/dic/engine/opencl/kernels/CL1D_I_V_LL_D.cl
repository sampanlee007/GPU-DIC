constant sampler_t sampler = CLK_NORMALIZED_COORDS_FALSE | CLK_ADDRESS_CLAMP_TO_EDGE | CLK_FILTER_NEAREST;

%INT%

/**
 * @author Petr Jecmen, Technical University of Liberec
 */
kernel void CL1D_I_V_LL_D(
    read_only image2d_t imageA, read_only image2d_t imageB, 
    global read_only int2 * facets, global read_only float2 * facetCenters,
    global read_only float * deformationLimits, global read_only int * deformationCounts,
    global write_only float * result,        
    const int imageWidth, const int deformationCount,
    const int facetSize, const int facetCount,
    const int groupCountPerFacet,
    const int facetSubCount, const int facetBase,
    const int deformationSubCount, const int deformationBase) 
{        
    //// ID checks    
    // facet
    const size_t groupId = get_group_id(0);
    const size_t facetId = (groupId / groupCountPerFacet) + facetBase;
    if (facetId >= facetBase + facetSubCount || facetId >= facetCount) {
        return;
    }        
    // deformation    
    const int groupSubId = groupId % groupCountPerFacet;
    const size_t localId = get_local_id(0);
    const size_t groupSize = get_local_size(0);
    const int deformationId = groupSubId * groupSize + localId;    
    // index computation
    const int facetSize2 = facetSize * facetSize;    
    const int baseIndexFacet = facetId * facetSize2;     
    const int baseIndexDeformation = deformationId * 6;        
    // load facet to local memory    
    local int2 facetLocal[-1*-1];    
    if (groupSize >= facetSize2) {
        if (localId < facetSize2) {
            facetLocal[localId] = facets[baseIndexFacet + localId];
        }    
    } else {
        const int runCount = facetSize2 / groupSize;
        int index;
        for (int i = 0; i < runCount; i++) {
            index = i*groupSize + localId;
            facetLocal[index] = facets[baseIndexFacet + index];
        }
        const int rest = facetSize2 % groupSize;
        if (localId < rest) {
            index = groupSize * runCount + localId;
            facetLocal[index] = facets[baseIndexFacet + index];
        }
    }        
    barrier(CLK_LOCAL_MEM_FENCE);
    if (deformationId >= deformationCount) {
        return;
    }
    float deformation[%DEF_D%];
    %DEF_C%
    // deform facet
    float2 deformedFacet[-1*-1];
    int i2;
    float2 coords, def;   
    for (int i = 0; i < facetSize2; i++) {
        coords = convert_float2(facetLocal[i]);       

        def = coords - facetCenters[facetId];
        
        deformedFacet[i] = (float2)(%DEF_X%, %DEF_Y%);
    }
    // compute correlation using ZNCC
    float deformedI[-1*-1];
    float facetI[-1*-1];
    float meanF = 0;
    float meanG = 0; 
    for (int i = 0; i < facetSize2; i++) {                                             
        facetI[i] = read_imageui(imageA, sampler, facetLocal[i]).x;
        meanF += facetI[i];
        
        deformedI[i] = interpolate(deformedFacet[i].x, deformedFacet[i].y, imageB);
        meanG += deformedI[i];
    } 
    meanF /= (float) facetSize2;
    meanG /= (float) facetSize2;    
    
    float deltaF = 0;
    float deltaG = 0;   
    for (int i = 0; i < facetSize2; i++) {                                             
        facetI[i] -= meanF;
        deltaF += facetI[i] * facetI[i];
                        
        deformedI[i] -= meanG;
        deltaG += deformedI[i] * deformedI[i];
    }    
    const float deltaFs = sqrt(deltaF);
    const float deltaGs = sqrt(deltaG);    
    
    float resultVal = 0;           
    for (int i = 0; i < facetSize2; i++) {              
        resultVal += facetI[i] * deformedI[i];
    }
    resultVal /= deltaFs * deltaGs;    
    
    //store result    
    result[facetId * deformationCount + deformationId] = resultVal;    
}